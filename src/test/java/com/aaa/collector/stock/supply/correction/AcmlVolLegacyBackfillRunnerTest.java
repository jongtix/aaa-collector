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
import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.KisShortSaleResponse;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * {@link AcmlVolLegacyBackfillRunner} 단위 테스트 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
 * REQ-SSVC-031, -032, -070, plan.md §M6).
 *
 * <p>종목×기간 윈도우 청킹(90일, {@link ShortSaleCollectionService#BACKFILL_LOOKBACK_CALENDAR_DAYS} 재사용)이
 * TR04 호출 횟수를 행 수보다 적게 유지함을 {@code windowFetchCount()}로 검증하고, 재조회 결과는 M3의 {@link
 * AcmlVolReconciliationGuard}에 전달해 3분기 판정을 받은 뒤 M4가 만든 {@link
 * ShortSaleDomesticRepository#updateTrack1Correction} 원자적 쓰기 경로를 그대로 재사용함을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AcmlVolLegacyBackfillRunner 단위 테스트")
class AcmlVolLegacyBackfillRunnerTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");

    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private AcmlVolReconciliationGuard guard;
    @Mock private ShortSaleCollectionService shortSaleCollectionService;
    @Mock private HealthyKeySelector healthyKeySelector;

    private AcmlVolLegacyBackfillRunner runner;

    @BeforeEach
    void setUp() {
        // 실제 KeyLeaseRegistry + mock HealthyKeySelector — openSession()이 진짜 LeaseSession을 생성한다
        // (ShortSaleVolRateCorrectionServiceTest와 동일 패턴).
        KeyLeaseRegistry keyLeaseRegistry = new KeyLeaseRegistry(healthyKeySelector);
        runner =
                new AcmlVolLegacyBackfillRunner(
                        shortSaleDomesticRepository,
                        dailyOhlcvRepository,
                        guard,
                        shortSaleCollectionService,
                        keyLeaseRegistry);
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

    private ShortSaleDomestic legacyRow(
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
                        .acmlVol(null)
                        .volRateVerifiedAt(null)
                        .build();
        ReflectionTestUtils.setField(row, "id", id);
        return row;
    }

    private DailyOhlcv dailyOhlcvOf(Stock stock, LocalDate tradeDate, long volume) {
        return DailyOhlcv.builder()
                .stock(stock)
                .tradeDate(tradeDate)
                .openPrice(BigDecimal.ZERO)
                .highPrice(BigDecimal.ZERO)
                .lowPrice(BigDecimal.ZERO)
                .closePrice(BigDecimal.ZERO)
                .volume(volume)
                .tradingValue(0L)
                .build();
    }

    private KisShortSaleResponse.ShortSaleRow tr04Row(String date, String qty, String acmlVol) {
        return new KisShortSaleResponse.ShortSaleRow(
                date, qty, "0", "0", "0", "0", "0", "0", "0", acmlVol);
    }

    private KisShortSaleResponse windowResponse(KisShortSaleResponse.ShortSaleRow... rows) {
        return new KisShortSaleResponse("0", "MCA00000", "조회되었습니다.", List.of(rows));
    }

    @Test
    @DisplayName("모든 키 죽음 — 배치 skip, 조회조차 발생하지 않음")
    void allKeysDead_skipsWithoutQuerying() {
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of());

        AcmlVolLegacyBackfillResult result = runner.run();

        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(0, 0, 0, 0));
        verify(shortSaleDomesticRepository, never())
                .findTrack1LegacyBacklogStockIds(anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("동일 종목 90일 이내 2행 — 1회 윈도우 호출로 병합, 둘 다 MATCHED 정정")
    void rowsWithin90Days_mergeIntoSingleWindowCall() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 1L);
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 2, 10); // 36일 후 — 90일 윈도우 내
        ShortSaleDomestic row1 = legacyRow(stock, d1, 10_000L, new BigDecimal("2.00"), 100L);
        ShortSaleDomestic row2 = legacyRow(stock, d2, 5_000L, new BigDecimal("1.00"), 101L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(1L))
                .thenReturn(List.of(row1, row2));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(d1), eq(d2)))
                .thenReturn(
                        windowResponse(
                                tr04Row("20260105", "10000", "500000"),
                                tr04Row("20260210", "5000", "500000")));
        when(guard.reconcile(new BigDecimal("2.00"), 10_000L, 500_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
        when(guard.reconcile(new BigDecimal("1.00"), 5_000L, 500_000L, 5_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(1L, List.of(d1)))
                .thenReturn(List.of(dailyOhlcvOf(stock, d1, 1_000_000L)));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(1L, List.of(d2)))
                .thenReturn(List.of(dailyOhlcvOf(stock, d2, 1_000_000L)));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert — 1건의 TR04 호출로 2행 모두 정정(call-volume 축소 검증, plan.md §M6)
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(2, 0, 0, 1));
        verify(shortSaleCollectionService, times(1))
                .fetchLegacyBackfillWindow(any(LeaseSession.class), eq("005930"), eq(d1), eq(d2));
        verify(shortSaleDomesticRepository)
                .updateTrack1Correction(
                        eq(100L),
                        eq(500_000L),
                        eq(new BigDecimal("1.00")),
                        any(LocalDateTime.class));
        verify(shortSaleDomesticRepository)
                .updateTrack1Correction(
                        eq(101L),
                        eq(500_000L),
                        eq(new BigDecimal("0.50")),
                        any(LocalDateTime.class));
    }

    @Test
    @DisplayName("동일 종목 90일 초과 간격 2행 — 2회 윈도우 호출로 분리 청킹")
    void rowsExceeding90Days_splitIntoTwoWindowCalls() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("007120", 2L);
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 6, 1); // 147일 후 — 90일 윈도우 초과
        ShortSaleDomestic row1 = legacyRow(stock, d1, 2_000L, new BigDecimal("0.20"), 200L);
        ShortSaleDomestic row2 = legacyRow(stock, d2, 3_000L, new BigDecimal("0.30"), 201L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(2L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(2L))
                .thenReturn(List.of(row1, row2));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("007120"), eq(d1), eq(d1)))
                .thenReturn(windowResponse(tr04Row("20260105", "10000", "1000000")));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("007120"), eq(d2), eq(d2)))
                .thenReturn(windowResponse(tr04Row("20260601", "10000", "1000000")));
        when(guard.reconcile(new BigDecimal("0.20"), 2_000L, 1_000_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.eventAdjusted(1_000_000L));
        when(guard.reconcile(new BigDecimal("0.30"), 3_000L, 1_000_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.eventAdjusted(1_000_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(2L, List.of(d1)))
                .thenReturn(List.of(dailyOhlcvOf(stock, d1, 500_000L)));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(2L, List.of(d2)))
                .thenReturn(List.of(dailyOhlcvOf(stock, d2, 500_000L)));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert — 90일 초과 간격은 별도 윈도우로 분리되어 2회 호출
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(2, 0, 0, 2));
        verify(shortSaleCollectionService, times(1))
                .fetchLegacyBackfillWindow(any(LeaseSession.class), eq("007120"), eq(d1), eq(d1));
        verify(shortSaleCollectionService, times(1))
                .fetchLegacyBackfillWindow(any(LeaseSession.class), eq("007120"), eq(d2), eq(d2));
    }

    @Test
    @DisplayName("REVISION_SUSPECTED — 정정 스킵, acml_vol 미충전(자동 재시도 대상 유지)")
    void revisionSuspected_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 3L);
        LocalDate date = LocalDate.of(2026, 7, 20);
        ShortSaleDomestic row = legacyRow(stock, date, 15_600L, new BigDecimal("1.56"), 300L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(3L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(3L))
                .thenReturn(List.of(row));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(date), eq(date)))
                .thenReturn(windowResponse(tr04Row("20260720", "25700", "1000000")));
        when(guard.reconcile(new BigDecimal("1.56"), 15_600L, 1_000_000L, 25_700L))
                .thenReturn(AcmlVolReconciliationResult.revisionSuspected());

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(0, 1, 0, 1));
        verify(shortSaleDomesticRepository, never())
                .updateTrack1Correction(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("EC-3류 — 윈도우 응답에 대상 거래일 누락: skip, 나머지 행은 정상 처리")
    void targetDateMissingFromWindowResponse_skipsGracefully() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 4L);
        LocalDate d1 = LocalDate.of(2026, 3, 1);
        LocalDate d2 = LocalDate.of(2026, 3, 10);
        ShortSaleDomestic row1 = legacyRow(stock, d1, 1_000L, new BigDecimal("1.00"), 400L);
        ShortSaleDomestic row2 = legacyRow(stock, d2, 2_000L, new BigDecimal("2.00"), 401L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(4L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(4L))
                .thenReturn(List.of(row1, row2));
        // 응답에 d1(3/1)만 포함되고 d2(3/10)는 누락 — 상장폐지 등으로 TR04가 일부 날짜를 반환하지 않는 경우(EC-3류)
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(d1), eq(d2)))
                .thenReturn(windowResponse(tr04Row("20260301", "10000", "1000000")));
        when(guard.reconcile(new BigDecimal("1.00"), 1_000L, 1_000_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(1_000_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(4L, List.of(d1)))
                .thenReturn(List.of(dailyOhlcvOf(stock, d1, 1_000_000L)));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert — d1은 정정, d2는 응답에 없어 skip
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(1, 0, 1, 1));
        verify(shortSaleDomesticRepository, never())
                .updateTrack1Correction(eq(401L), anyLong(), any(), any());
    }

    @Test
    @DisplayName("daily_ohlcv.volume=0 — 재계산 분모 0, skip, UPDATE 미호출")
    void dailyOhlcvVolumeZero_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 9L);
        LocalDate date = LocalDate.of(2026, 6, 5);
        ShortSaleDomestic row = legacyRow(stock, date, 10_000L, new BigDecimal("1.00"), 900L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(9L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(9L))
                .thenReturn(List.of(row));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(date), eq(date)))
                .thenReturn(windowResponse(tr04Row("20260605", "10000", "500000")));
        when(guard.reconcile(new BigDecimal("1.00"), 10_000L, 500_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(9L, List.of(date)))
                .thenReturn(List.of(dailyOhlcvOf(stock, date, 0L)));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(0, 0, 1, 1));
        verify(shortSaleDomesticRepository, never())
                .updateTrack1Correction(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("EC-1 — 매칭되는 daily_ohlcv 행 없음: skip, UPDATE 미호출")
    void dailyOhlcvMissing_skipsWithoutUpdate() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 5L);
        LocalDate date = LocalDate.of(2026, 6, 5);
        ShortSaleDomestic row = legacyRow(stock, date, 10_000L, new BigDecimal("1.00"), 500L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(5L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(5L))
                .thenReturn(List.of(row));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(date), eq(date)))
                .thenReturn(windowResponse(tr04Row("20260605", "10000", "500000")));
        when(guard.reconcile(new BigDecimal("1.00"), 10_000L, 500_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(500_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(5L, List.of(date)))
                .thenReturn(List.of());

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(0, 0, 1, 1));
        verify(shortSaleDomesticRepository, never())
                .updateTrack1Correction(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("TR04 재조회 실패(재시도 소진) — 윈도우 전체 skip, 나머지 종목은 정상 진행")
    void windowFetchFails_skipsWindowRowsGracefully() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stock = stockOf("005930", 6L);
        LocalDate date = LocalDate.of(2026, 6, 5);
        ShortSaleDomestic row = legacyRow(stock, date, 10_000L, new BigDecimal("1.00"), 600L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(6L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(6L))
                .thenReturn(List.of(row));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(date), eq(date)))
                .thenThrow(new KisRateLimitException("isa", "EGW00201"));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert — 재조회 실패 시 해당 윈도우 행 전부 skip, 예외 전파 없이 정상 종료
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(0, 0, 1, 1));
        verify(shortSaleDomesticRepository, never())
                .updateTrack1Correction(anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("복수 종목 — stockId 커서 페이지네이션으로 전 종목 순회")
    void multipleStocksAcrossPages_processesAllViaCursor() throws Exception {
        // Arrange
        when(healthyKeySelector.selectHealthy()).thenReturn(List.of(ISA));
        Stock stockA = stockOf("005930", 7L);
        Stock stockB = stockOf("000660", 8L);
        LocalDate dateA = LocalDate.of(2026, 5, 1);
        LocalDate dateB = LocalDate.of(2026, 5, 2);
        ShortSaleDomestic rowA = legacyRow(stockA, dateA, 1_000L, new BigDecimal("1.00"), 700L);
        ShortSaleDomestic rowB = legacyRow(stockB, dateB, 2_000L, new BigDecimal("2.00"), 800L);

        when(shortSaleDomesticRepository.findTrack1LegacyBacklogStockIds(
                        anyLong(), any(Pageable.class)))
                .thenReturn(List.of(7L))
                .thenReturn(List.of(8L))
                .thenReturn(List.of());
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(7L))
                .thenReturn(List.of(rowA));
        when(shortSaleDomesticRepository.findTrack1LegacyBacklogByStock(8L))
                .thenReturn(List.of(rowB));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("005930"), eq(dateA), eq(dateA)))
                .thenReturn(windowResponse(tr04Row("20260501", "10000", "1000000")));
        when(shortSaleCollectionService.fetchLegacyBackfillWindow(
                        any(LeaseSession.class), eq("000660"), eq(dateB), eq(dateB)))
                .thenReturn(windowResponse(tr04Row("20260502", "10000", "1000000")));
        when(guard.reconcile(new BigDecimal("1.00"), 1_000L, 1_000_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(1_000_000L));
        when(guard.reconcile(new BigDecimal("2.00"), 2_000L, 1_000_000L, 10_000L))
                .thenReturn(AcmlVolReconciliationResult.matched(1_000_000L));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(7L, List.of(dateA)))
                .thenReturn(List.of(dailyOhlcvOf(stockA, dateA, 1_000_000L)));
        when(dailyOhlcvRepository.findByStockIdAndTradeDateIn(8L, List.of(dateB)))
                .thenReturn(List.of(dailyOhlcvOf(stockB, dateB, 1_000_000L)));

        // Act
        AcmlVolLegacyBackfillResult result = runner.run();

        // Assert — 두 종목 모두 처리, 3번째 페이지 조회로 종료(빈 목록)
        assertThat(result).isEqualTo(new AcmlVolLegacyBackfillResult(2, 0, 0, 2));
        verify(shortSaleDomesticRepository, times(3))
                .findTrack1LegacyBacklogStockIds(anyLong(), any(Pageable.class));
    }
}
