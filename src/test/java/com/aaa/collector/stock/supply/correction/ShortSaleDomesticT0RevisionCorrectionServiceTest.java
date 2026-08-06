package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.token.HealthyKeySelector;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ShortSaleDomesticT0RevisionCorrectionService} 단위 테스트
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-010~012, -020~022, -030, plan.md §M5).
 *
 * <p>TR04 라이브 재조회를 대체하는 {@link ShortSaleCollectionService} mock으로, M3의 3분기 판정 가드({@link
 * AcmlVolReconciliationGuard})를 전혀 거치지 않고 라이브 값을 그대로 채택함을 검증한다(REQ-T0R-020). "대상 재확인 절차"(plan.md
 * §M5) — {@code closingWindowEndDate}가 호출마다 캐싱 없이 그대로 repository 조회에 전달됨을 검증해 매 실행 재계산 계약을 지킴을
 * 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleDomesticT0RevisionCorrectionService 단위 테스트")
class ShortSaleDomesticT0RevisionCorrectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private ShortSaleCollectionService shortSaleCollectionService;
    @Mock private HealthyKeySelector healthyKeySelector;

    private ShortSaleDomesticT0RevisionCorrectionService service;

    @BeforeEach
    void setUp() {
        // 실제 KeyLeaseRegistry + mock HealthyKeySelector — openSession()이 진짜 LeaseSession을 생성한다
        // (ShortSaleVolRateCorrectionServiceTest와 동일 패턴).
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        service =
                new ShortSaleDomesticT0RevisionCorrectionService(
                        shortSaleDomesticRepository, shortSaleCollectionService, keyLeaseRegistry);
    }

    private Stock stockOf(String symbol, long id) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(stock, "id", id);
        return stock;
    }

    private ShortSaleDomestic shortSaleRow(
            Stock stock, LocalDate tradeDate, long qty, BigDecimal rate, long id) {
        ShortSaleDomestic row =
                ShortSaleDomestic.builder()
                        .stock(stock)
                        .tradeDate(tradeDate)
                        .shortSellQty(qty)
                        .shortSellVolRate(rate)
                        .shortSellAmt(0L)
                        .shortSellAmtRate(BigDecimal.ZERO)
                        .shortSellAccQty(0L)
                        .shortSellAccQtyRate(BigDecimal.ZERO)
                        .shortSellAccAmt(0L)
                        .shortSellAccAmtRate(BigDecimal.ZERO)
                        .build();
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private KisShortSaleResponse tr04Response(String date, String sstsCntgQty, String sstsVolRlim) {
        return new KisShortSaleResponse(
                "0",
                "MCA00000",
                "조회되었습니다.",
                List.of(
                        new KisShortSaleResponse.ShortSaleRow(
                                date,
                                sstsCntgQty,
                                sstsVolRlim,
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                "0",
                                "0")));
    }

    @Test
    @DisplayName("모든 키 죽음 — 배치 skip, 조회조차 발생하지 않음")
    void allKeysDead_skipsWithoutQuerying() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(LocalDate.of(2026, 8, 6));

        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(0, 0));
        verify(shortSaleDomesticRepository, never())
                .findT0RevisionCandidateBatch(any(), any(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("REQ-T0R-020 — 라이브 재조회 확정치를 재계산·가드 없이 그대로 채택해 원자적 UPDATE")
    void liveRevision_updatesQtyAndRateDirectly() throws Exception {
        // Arrange — 저장값(예비치): qty=15,600, rate=1.56 → 확정치로 리비전(사전조사 §3.5 07-20 실측 사례)
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 1L);
        LocalDate date = LocalDate.of(2026, 7, 20);
        ShortSaleDomestic row = shortSaleRow(stock, date, 15_600L, new BigDecimal("1.56"), 100L);
        LocalDate closingWindowEndDate = LocalDate.of(2026, 8, 6);
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        eq(ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE),
                        eq(closingWindowEndDate),
                        anyLong(),
                        any(Pageable.class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("005930"), eq(date)))
                .thenReturn(tr04Response("20260720", "25700", "2.57"));

        // Act — 재계산·가드 판정 없이 라이브 값 그대로 채택
        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(closingWindowEndDate);

        // Assert
        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(1, 0));
        verify(shortSaleDomesticRepository)
                .updateT0RevisionCorrection(eq(100L), eq(25_700L), eq(new BigDecimal("2.57")));
    }

    @Test
    @DisplayName("EC — TR04 재조회 결과 없음(상장폐지 등): skip, UPDATE 미호출")
    void emptyLiveResponse_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("999999", 2L);
        LocalDate date = LocalDate.of(2026, 7, 1);
        ShortSaleDomestic row = shortSaleRow(stock, date, 1_000L, new BigDecimal("1.00"), 200L);
        LocalDate closingWindowEndDate = LocalDate.of(2026, 8, 6);
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("999999"), eq(date)))
                .thenReturn(new KisShortSaleResponse("0", "MCA00000", "조회되었습니다.", List.of()));

        // Act
        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(closingWindowEndDate);

        // Assert
        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(0, 1));
        verify(shortSaleDomesticRepository, never())
                .updateT0RevisionCorrection(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("TR04 재조회 실패(재시도 소진) — skip, UPDATE 미호출")
    void tr04Failure_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 3L);
        LocalDate date = LocalDate.of(2026, 7, 5);
        ShortSaleDomestic row = shortSaleRow(stock, date, 1_000L, new BigDecimal("1.00"), 300L);
        LocalDate closingWindowEndDate = LocalDate.of(2026, 8, 6);
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("005930"), eq(date)))
                .thenThrow(new KisRateLimitException("isa", "재시도 소진"));

        // Act
        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(closingWindowEndDate);

        // Assert
        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(0, 1));
        verify(shortSaleDomesticRepository, never())
                .updateT0RevisionCorrection(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("라이브 응답 숫자 파싱 실패 — skip, UPDATE 미호출")
    void malformedLiveResponse_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 6L);
        LocalDate date = LocalDate.of(2026, 7, 3);
        ShortSaleDomestic row = shortSaleRow(stock, date, 1_000L, new BigDecimal("1.00"), 600L);
        LocalDate closingWindowEndDate = LocalDate.of(2026, 8, 6);
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("005930"), eq(date)))
                .thenReturn(tr04Response("20260703", "N/A", "1.00"));

        // Act
        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(closingWindowEndDate);

        // Assert
        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(0, 1));
        verify(shortSaleDomesticRepository, never())
                .updateT0RevisionCorrection(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("대상 재확인 절차(plan.md §M5) — closingWindowEndDate가 호출마다 캐싱 없이 그대로 조회에 전달됨")
    void closingWindowEndDate_isNotCachedAcrossCalls() {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA), List.of(ISA));
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());
        LocalDate firstRun = LocalDate.of(2026, 8, 1);
        LocalDate secondRun = LocalDate.of(2026, 8, 10); // 다음 실행 시 상한이 전진했다고 가정

        // Act
        service.correctT0Revisions(firstRun);
        service.correctT0Revisions(secondRun);

        // Assert — 각 호출이 그 시점의 상한을 그대로 조회에 전달(캐싱 없음)
        verify(shortSaleDomesticRepository)
                .findT0RevisionCandidateBatch(
                        eq(ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE),
                        eq(firstRun),
                        eq(0L),
                        any(Pageable.class));
        verify(shortSaleDomesticRepository)
                .findT0RevisionCandidateBatch(
                        eq(ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE),
                        eq(secondRun),
                        eq(0L),
                        any(Pageable.class));
    }

    @Test
    @DisplayName("REQ-T0R-011 하한 리터럴 — CLOSING_WINDOW_START_DATE == 2026-06-29")
    void closingWindowStartDate_isReqT0R011Literal() {
        assertThat(ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE)
                .isEqualTo(LocalDate.of(2026, 6, 29));
    }

    @Test
    @DisplayName("배치 크기는 1보다 크고 유한하다")
    void batchSizeIsFiniteAndGreaterThanOne() {
        assertThat(ShortSaleDomesticT0RevisionCorrectionService.BATCH_SIZE).isGreaterThan(1);
    }

    @Test
    @DisplayName("배치 페이지네이션 — 2페이지 이상 처리 시 전량 반영")
    void multiplePages_processesAllRows() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock1 = stockOf("005930", 10L);
        Stock stock2 = stockOf("000660", 11L);
        LocalDate date1 = LocalDate.of(2026, 7, 1);
        LocalDate date2 = LocalDate.of(2026, 7, 2);
        ShortSaleDomestic row1 = shortSaleRow(stock1, date1, 1_000L, new BigDecimal("1.00"), 400L);
        ShortSaleDomestic row2 = shortSaleRow(stock2, date2, 2_000L, new BigDecimal("2.00"), 401L);
        LocalDate closingWindowEndDate = LocalDate.of(2026, 8, 6);
        when(shortSaleDomesticRepository.findT0RevisionCandidateBatch(
                        any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(row1))
                .thenReturn(List.of(row2))
                .thenReturn(List.of());
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("005930"), eq(date1)))
                .thenReturn(tr04Response("20260701", "1100", "1.10"));
        when(shortSaleCollectionService.fetchSingleDate(
                        any(LeaseSession.class), eq("000660"), eq(date2)))
                .thenReturn(tr04Response("20260702", "2200", "2.20"));

        // Act
        ShortSaleT0RevisionCorrectionResult result =
                service.correctT0Revisions(closingWindowEndDate);

        // Assert
        assertThat(result).isEqualTo(new ShortSaleT0RevisionCorrectionResult(2, 0));
        verify(shortSaleDomesticRepository, times(3))
                .findT0RevisionCandidateBatch(any(), any(), anyLong(), any(Pageable.class));
        verify(shortSaleDomesticRepository)
                .updateT0RevisionCorrection(eq(400L), eq(1_100L), eq(new BigDecimal("1.10")));
        verify(shortSaleDomesticRepository)
                .updateT0RevisionCorrection(eq(401L), eq(2_200L), eq(new BigDecimal("2.20")));
    }
}
