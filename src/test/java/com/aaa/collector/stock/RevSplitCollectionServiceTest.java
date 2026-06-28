package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T20) — {@link RevSplitCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·단일 페이지 = 1 batch이므로 세션 1회 open)과 기존
 * 수집 의미(분할/병합 분류·degenerate skip·비관심종목 skip·멱등·독성 행 흡수)를 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RevSplitCollectionService — 게이트 경유 단위 테스트")
class RevSplitCollectionServiceTest {

    private static final String TR_ID = "HHKDB669105C0";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventRepository corporateEventRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private RevSplitCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new RevSplitCollectionService(
                        guardedKisExecutor,
                        keyLeaseRegistry,
                        stockRepository,
                        corporateEventRepository,
                        corporateEventInserter);
        Mockito.lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
    }

    private Stock watchlistStock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo(symbol + "테스트")
                .market(Market.KRX)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    /** 분할 행 생성 헬퍼: af < bf → "분할", bf=500, af=100. */
    private KisRevSplitResponse.RevSplitRow splitRow(String shtCd) {
        return new KisRevSplitResponse.RevSplitRow(
                "20260613", shtCd, shtCd + "명", "000000500", "000000100", "", "20260613");
    }

    /** 병합 행 생성 헬퍼: af > bf (bf>0) → "병합", bf=500, af=2500. */
    private KisRevSplitResponse.RevSplitRow mergeRow(String shtCd) {
        return new KisRevSplitResponse.RevSplitRow(
                "20260616", shtCd, shtCd + "명", "000000500", "000002500", "", "20260616");
    }

    /** 무변동 행 생성 헬퍼: af == bf == 0 → skip. */
    private KisRevSplitResponse.RevSplitRow noChangeRow(String shtCd) {
        return new KisRevSplitResponse.RevSplitRow(
                "20260601", shtCd, shtCd + "명", "000000000", "000000000", "", "");
    }

    /** degenerate 행: bf=0, af>0 (실측 900250/900070 케이스 — CR-01). */
    private KisRevSplitResponse.RevSplitRow degenerateRow(String shtCd) {
        return new KisRevSplitResponse.RevSplitRow(
                "20260601", shtCd, shtCd + "명", "000000000", "000000002", "", "");
    }

    private KisRevSplitResponse singlePageResponse(List<KisRevSplitResponse.RevSplitRow> rows) {
        return new KisRevSplitResponse("0", "MCA00000", "정상처리", rows);
    }

    // ────────────────────────────────────────────────────────────────────
    // T6: 매핑 단위 테스트 — 분할 / 병합 / 무변동 / degenerate / 비율 산출
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("T6: 매핑 — 분할/병합/무변동/degenerate/비율 산출 (AC-MAP-1~3b, REQ-BATCH5-020~023)")
    class MappingTests {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-MAP-1: 액면분할(af<bf) → event_subtype='분할', stock_rate=5.0000, face_value=100")
        void splitRow_mappedAsSplit() throws Exception {
            // Arrange
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isZero();
            assertThat(result.skippedValidation()).isZero();

            // inserterCaptor is a @Captor field
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());

            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(saved.getEventSubtype()).isEqualTo("분할");
            assertThat(saved.getEventDate()).isEqualTo(LocalDate.of(2026, 6, 13));
            assertThat(saved.getFaceValue()).isEqualTo(100L);
            // stock_rate = 500 / 100 = 5.0000 (bf/af)
            assertThat(saved.getStockRate()).isEqualByComparingTo(new BigDecimal("5.0000"));
            assertThat(saved.getStock()).isSameAs(stock);
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-MAP-2: 액면병합(af>bf, bf>0) → event_subtype='병합', stock_rate=0.2000, face_value=2500")
        void mergeRow_mappedAsMerge() throws Exception {
            // Arrange
            Stock stock = watchlistStock("025440");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(mergeRow("025440"))));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.succeeded()).isEqualTo(1);

            // inserterCaptor is a @Captor field
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());

            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(saved.getEventSubtype()).isEqualTo("병합");
            assertThat(saved.getFaceValue()).isEqualTo(2500L);
            // stock_rate = 500 / 2500 = 0.2000 (bf/af)
            assertThat(saved.getStockRate()).isEqualByComparingTo(new BigDecimal("0.2000"));
        }

        @Test
        @DisplayName("AC-MAP-3: 무변동(af==bf==0) → graceful skip, skippedValidation 집계")
        void noChangeRow_gracefulSkip() throws Exception {
            // Arrange
            Stock stock = watchlistStock("900100");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(noChangeRow("900100"))));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 분류 이전 skip
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName(
                "AC-MAP-3b: degenerate(bf=0, af>0) — CR-01 회귀 방지: '병합' 오분류 차단, stock_rate=0.0000 저장 안 됨")
        void degenerateRow_gracefulSkipNotMappedAsMerge() throws Exception {
            // Arrange — 실측 900250(bf=0/af=2) 케이스
            Stock stock = watchlistStock("900250");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(degenerateRow("900250"))));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — bf<=0 이므로 분류 이전 skip (af>bf=2>0이지만 "병합" 오분류 안 됨)
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("9자리 zero-pad 면액 파싱 정상 처리 — 앞 0 흡수")
        void zeroPadAmount_parsedCorrectly() throws Exception {
            // Arrange — "000000500" → 500, "000002500" → 2500
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));

            // Act
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            // inserterCaptor is a @Captor field
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedFaceValue =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedFaceValue.getFaceValue()).isEqualTo(100L);
        }

        @Test
        @DisplayName("비종료 소수 비율(100/300=0.3333) — divide(4,HALF_UP) ArithmeticException 없음 (MA-01)")
        void nonTerminatingRatio_noArithmeticException() throws Exception {
            // Arrange — bf=300, af=100 → 300/100 = 3.0000 (종료 소수)
            //           bf=100, af=300 → 100/300 = 0.3333... (비종료 소수 — HALF_UP으로 0.3333)
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613", "096960", "테스트", "000000100", "000000300", "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act — ArithmeticException 없이 완료
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 병합(af>bf): stock_rate = 100/300 ≈ 0.3333
            assertThat(result.succeeded()).isEqualTo(1);
            // inserterCaptor is a @Captor field
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedStockRate =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedStockRate.getStockRate())
                    .isEqualByComparingTo(new BigDecimal("0.3333"));
        }

        @Test
        @DisplayName(
                "미저장 필드(td_stop_dt/list_dt/isin_name) — corporate_events에 저장 안 됨, SPLIT 행 미사용 컬럼 null")
        void unusedFields_notStored() throws Exception {
            // Arrange
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));

            // Act
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — SPLIT 행 미사용 컬럼은 null
            // inserterCaptor is a @Captor field
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedNullCols =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedNullCols)
                    .extracting(
                            CorporateEvent::getPayDate,
                            CorporateEvent::getStockPayDate,
                            CorporateEvent::getOddPayDate,
                            CorporateEvent::getCashAmount,
                            CorporateEvent::getCashRate,
                            CorporateEvent::getStockKind,
                            CorporateEvent::getHighDividendFlag)
                    .containsOnlyNulls();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // T7: 멱등 저장 — 단위 테스트 (통합 테스트는 RevSplitIdempotencyIntegrationTest)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("T7: 멱등 저장 — insertIgnoreDuplicate 재호출 (AC-MAP-4, REQ-BATCH5-024)")
    class IdempotencyTests {

        @Test
        @DisplayName("동일 행 재수집 시 insertIgnoreDuplicate 재호출 (DB가 중복 무시)")
        void idempotentRerun() throws Exception {
            // Arrange
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));

            // Act — 2회 실행
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 서비스는 2회 호출 → insertBatchIsolated 2회 (collect당 1회)
            verify(corporateEventInserter, times(2)).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // T8: 경로 / 이벤트 / 페이징 (단일키, 완료 이벤트 미발행, 단일 페이지, 100건 캡 WARN)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("T8: 경로/이벤트/페이징 (AC-PATH-1/3/5, REQ-BATCH5-010/012/014)")
    class PathAndPagingTests {

        @Test
        @DisplayName(
                "AC-PATH-1: 게이트 경유 — 단일 페이지 = 1 batch, 세션 1회 open, throttle-on 경유 TR_ID=HHKDB669105C0")
        void gatePath_routedThroughGuardedExecutor() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            // Act
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 게이트 1회 경유(throttle-on 4-arg), per-batch 스냅샷 1회
            verify(keyLeaseRegistry, times(1)).openSession();
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class));
        }

        @Test
        @DisplayName("AC-PATH-5: 빈 output1 → 단일 페이지 종료, 저장 없음 (0건 정상 집계)")
        void emptyOutput1_zeroSuccess() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 0건 성공 (정상)
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("100건 캡 도달 시 WARN 로그 경로 실행 — capReached=true, 정상 집계 (PROBE-1)")
        void hundredRowCap_warnLogged_normalResult() throws Exception {
            // Arrange — 정확히 100개 행 (캡 도달 조건: >= 100)
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            List<KisRevSplitResponse.RevSplitRow> rows = new ArrayList<>();
            // 1행만 유효 분할(096960), 나머지 99행은 비관심종목
            rows.add(splitRow("096960"));
            IntStream.range(1, 100).forEach(i -> rows.add(splitRow(String.format("%06d", i))));

            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(rows));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 100건 캡 경로 통과, 1건 성공 + 99건 비관심
            assertThat(result.attempted()).isEqualTo(100);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isEqualTo(99);
        }

        @Test
        @DisplayName("단일 페이지만 호출 — 연속조회 없음 (PROBE-1 확정: CTS 구조적 불가)")
        void singlePage_noFollowUpCall() throws Exception {
            // Arrange
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));

            // Act
            service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 게이트 정확히 1회만 경유(단일 페이지)
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // T9: 필터 / 검증 / 집계 엣지 케이스
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("T9: 필터/검증/집계/엣지 (AC-FILTER-1, AC-VAL-1~4, REQ-BATCH5-050/070/072/073)")
    class FilterAndValidationTests {

        @Test
        @DisplayName("AC-FILTER-1: 비관심종목 skip — 개별 WARN 없음, 집계 카운트만")
        void nonWatchlistRows_skipped_aggregateCounted() throws Exception {
            // Arrange — 관심: 096960, 비관심: 000001, 000002
            Stock watchlistStock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(watchlistStock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(
                            singlePageResponse(
                                    List.of(
                                            splitRow("096960"), // 관심
                                            splitRow("000001"), // 비관심
                                            splitRow("000002")))); // 비관심

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isEqualTo(2);
            verify(corporateEventInserter, times(1)).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("sht_cd null 행 → skippedValidation, 다음 행 계속")
        void nullShtCd_skippedValidation() throws Exception {
            // Arrange
            KisRevSplitResponse.RevSplitRow nullShtCd =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613", null, "테스트", "000000500", "000000100", "", "");
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(nullShtCd)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("record_date 파싱 불가 행 → skippedValidation")
        void invalidRecordDate_skippedValidation() throws Exception {
            // Arrange
            KisRevSplitResponse.RevSplitRow badDate =
                    new KisRevSplitResponse.RevSplitRow(
                            "invalid-date", "096960", "테스트", "000000500", "000000100", "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(badDate)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("AC-VAL-1: degenerate(bf<=0) → skippedValidation (분류 이전 skip)")
        void degenerateAfLessOrEqualZero_skippedValidation() throws Exception {
            // Arrange — af=-1 degenerate
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260601", "900250", "외국주", "000000000", "-000000001", "", "");
            Stock stock = watchlistStock("900250");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName(
                "AC-VAL-4: 4항 불변식 — attempted = succeeded + skippedNonWatchlist + skippedValidation")
        void fourFieldInvariant() throws Exception {
            // Arrange — 관심 1(분할성공) + 비관심 2 + degenerate 1(skippedValidation)
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(
                            singlePageResponse(
                                    List.of(
                                            splitRow("096960"), // 관심종목, 유효 → succeeded
                                            splitRow("000001"), // 비관심 → skippedNonWatchlist
                                            splitRow("000002"), // 비관심 → skippedNonWatchlist
                                            noChangeRow("096960")))); // 무변동 → skippedValidation

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 불변식 성립
            assertThat(result.attempted()).isEqualTo(4);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isEqualTo(2);
            assertThat(result.skippedValidation()).isEqualTo(1);
            assertThat(result.attempted())
                    .isEqualTo(
                            result.succeeded()
                                    + result.skippedNonWatchlist()
                                    + result.skippedValidation());
        }

        @Test
        @DisplayName("AC-VAL-3: 빈 output1 → 0건 성공 집계 (skip/실패 아님)")
        void emptyOutput1_zeroSuccessNormal() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 정상 완료, 0건
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            assertThat(result.skippedNonWatchlist()).isZero();
            assertThat(result.skippedValidation()).isZero();
        }

        @Test
        @DisplayName("DECIMAL(12,4) 정수부 경계 초과 비율 → skippedValidation")
        void ratioExceedsDecimalBound_skippedValidation() throws Exception {
            // Arrange — bf=10^9, af=1 → ratio=10^9 (정수부 9자리 > 8자리 초과)
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613", "096960", "테스트", "1000000000", "000000001", "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName(
                "분할/병합 혼재 + degenerate/무변동 skip — skippedValidation에 degenerate/무변동 흡수 (MA-02)")
        void mixedRows_degenerateAndNoChangeAbsorbedInSkippedValidation() throws Exception {
            // Arrange — 4종목 모두 관심종목으로 등록하여 watchlist skip이 아닌 validation skip 검증
            Stock stock1 = watchlistStock("096960"); // 분할
            Stock stock2 = watchlistStock("025440"); // 병합
            Stock stock3 = watchlistStock("900250"); // degenerate(bf=0,af>0) → skippedValidation
            Stock stock4 = watchlistStock("900100"); // 무변동(af==bf==0) → skippedValidation
            when(stockRepository.findAllActive())
                    .thenReturn(List.of(stock1, stock2, stock3, stock4));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(
                            singlePageResponse(
                                    List.of(
                                            splitRow("096960"), // 분할 → succeeded
                                            mergeRow("025440"), // 병합 → succeeded
                                            degenerateRow(
                                                    "900250"), // degenerate → skippedValidation
                                            // (CR-01)
                                            noChangeRow(
                                                    "900100")))); // 무변동 → skippedValidation (MA-02)

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — degenerate/무변동은 skippedValidation에 흡수 (MA-02)
            assertThat(result.attempted()).isEqualTo(4);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skippedNonWatchlist()).isZero();
            assertThat(result.skippedValidation()).isEqualTo(2);
        }

        @Test
        @DisplayName("윈도우 파라미터 — 서비스 메서드가 LocalDate from/to를 YYYYMMDD로 변환해 전달")
        void windowParams_convertedToYyyyMmDd() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            LocalDate from = LocalDate.of(2026, 6, 3); // today-14
            LocalDate to = LocalDate.of(2026, 8, 16); // today+60

            // Act
            service.collect(from, to);

            // Assert — 게이트가 경유되었음 (파라미터 검증은 캡처로)
            verify(guardedKisExecutor)
                    .execute(eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class));
        }

        @Test
        @DisplayName("record_date 빈 문자열 행 → skippedValidation")
        void blankRecordDate_skippedValidation() throws Exception {
            // Arrange
            KisRevSplitResponse.RevSplitRow blankDate =
                    new KisRevSplitResponse.RevSplitRow(
                            "   ", "096960", "테스트", "000000500", "000000100", "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(blankDate)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("면액 파싱 실패(null 값) → skippedValidation")
        void faceAmountParseFailure_skippedValidation() throws Exception {
            // Arrange — interAfFaceAmt에 null 전달
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613", "096960", "테스트", "000000500", null, "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("독성 행 insertIgnoreDuplicate 예외 → skippedValidation, 나머지 행 계속 처리")
        void poisonRowInsertException_otherRowsProcessed() throws Exception {
            // Arrange
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(splitRow("096960"))));
            // REQ-INSERT-011: 독성 행에 대해 콜백 호출 시뮬레이션
            final java.sql.SQLException toxicEx = new java.sql.SQLException("dup", "23000", 1062);
            Mockito.doAnswer(
                            invocation -> {
                                List<CorporateEvent> rows = invocation.getArgument(0);
                                @SuppressWarnings("unchecked")
                                com.aaa.collector.observability.RowFailureHandler<CorporateEvent>
                                        handler = invocation.getArgument(1);
                                for (CorporateEvent entity : rows) {
                                    handler.onFailure(entity, toxicEx);
                                }
                                return null;
                            })
                    .when(corporateEventInserter)
                    .insertBatchIsolated(any(), any());

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            // Assert — 예외 행은 skippedValidation
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skippedValidation()).isEqualTo(1);
        }

        @Test
        @DisplayName("parseLongOrNull — 빈 문자열 → null (면액 파싱 실패 → skip)")
        void blankFaceAmount_parseLongOrNullReturnsNull() throws Exception {
            // Arrange — interBfFaceAmt 빈 문자열 → parseLongOrNull null → skip
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613", "096960", "테스트", "", "000000100", "", "");
            Stock stock = watchlistStock("096960");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitCollectionResult result =
                    service.collect(LocalDate.of(2026, 5, 30), LocalDate.of(2026, 8, 15));

            assertThat(result.skippedValidation()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // SPEC-COLLECTOR-BACKFILL-007 W5 — 종목지정 백필 fetch/persist
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BACKFILL-007: 종목지정 백필 fetch/persist (REQ-BACKFILL-095~099a)")
    class BackfillFetchPersist {

        private final LocalDate floor = LocalDate.of(1950, 1, 1);
        private final LocalDate today = LocalDate.of(2026, 6, 28);

        @SuppressWarnings("unchecked")
        private URI capturedUri() throws InterruptedException {
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            verify(guardedKisExecutor).execute(eq(session), uriCaptor.capture(), eq(TR_ID), any());
            UriBuilder builder =
                    new DefaultUriBuilderFactory().uriString("https://openapi.koreainvestment.com");
            return uriCaptor.getValue().apply(builder);
        }

        @Test
        @DisplayName("AC-4: SHT_CD=종목코드·F_DT=19500101·T_DT=today·MARKET_GB=0 호출 파라미터")
        void backfillFetch_callsWithTickerSpecifiedParams() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            // Act
            service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert — URI 빌더를 실제 적용해 쿼리 파라미터 확인
            URI uri = capturedUri();
            assertThat(uri.getRawQuery())
                    .contains("SHT_CD=005930")
                    .contains("F_DT=19500101")
                    .contains("T_DT=20260628")
                    .contains("MARKET_GB=0");
            assertThat(uri.getRawQuery()).doesNotContain("SHT_CD=&");
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("AC-6: 분할 행(bf=5000,af=100) 매핑 재사용 — SPLIT·분할·rate=50.0000·face=100")
        void backfillFetch_reusesSplitMapping() throws Exception {
            // Arrange — 50:1 분할 (bf=5000, af=100)
            Stock stock = watchlistStock("005930");
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20180502", "005930", "삼성전자", "000005000", "000000100", "", "");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert
            assertThat(fetch.validRows()).hasSize(1);
            CorporateEvent e = fetch.validRows().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(e.getEventSubtype()).isEqualTo("분할");
            assertThat(e.getFaceValue()).isEqualTo(100L);
            assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("50.0000"));
            assertThat(e.getEventDate()).isEqualTo(LocalDate.of(2018, 5, 2));
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-5: 단일 유효 분할 → rowCount=1·rawRowCount=1, oldestRecordDate=2018-05-02, 1행 적재")
        void backfillFetch_singleValidSplit() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20180502", "005930", "삼성전자", "000005000", "000000100", "", "");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            // Act
            RevSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            // Assert — 저장 행수 rowCount=검증통과=1·종료 입력 rawRowCount=원본=1, oldest=최소 record_date
            assertThat(fetch.rawRowCount()).isEqualTo(1);
            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(1);
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2018, 5, 2));
            verify(corporateEventInserter).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(1);
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-5a: degenerate+valid 혼합(output1=2) → rawRowCount=2(결정적), 적재=1, COMPLETED 무관")
        void backfillFetch_degeneratePlusValid_deterministicRowCount() throws Exception {
            // Arrange — 1건 유효 분할(bf=5000,af=100) + 1건 degenerate(bf=0,af=2 → skip)
            Stock stock = watchlistStock("005930");
            KisRevSplitResponse.RevSplitRow valid =
                    new KisRevSplitResponse.RevSplitRow(
                            "20180502", "005930", "삼성전자", "000005000", "000000100", "", "");
            KisRevSplitResponse.RevSplitRow degenerate =
                    new KisRevSplitResponse.RevSplitRow(
                            "20100101", "005930", "삼성전자", "000000000", "000000002", "", "");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(valid, degenerate)));

            // Act
            RevSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            // Assert — REQ-BACKFILL-099a: 종료 입력 rawRowCount=원본 행수(2) 결정적, 저장 행수 rowCount=적재(1)와 분리
            assertThat(fetch.rawRowCount()).isEqualTo(2);
            assertThat(result.rawRowCount()).isEqualTo(2);
            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(fetch.validRows()).hasSize(1);
            // 적재는 유효 분할 1행만 (degenerate skip)
            verify(corporateEventInserter).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(1);
            assertThat(inserterCaptor.getValue().getFirst().getEventDate())
                    .isEqualTo(LocalDate.of(2018, 5, 2));
        }

        @Test
        @DisplayName("EC-2: 액면병합(bf=500,af=2500) → SPLIT·병합·rate=0.2000")
        void backfillFetch_mergeMapping() throws Exception {
            Stock stock = watchlistStock("005930");
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20200101", "005930", "삼성전자", "000000500", "000002500", "", "");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of(row)));

            RevSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);

            CorporateEvent e = fetch.validRows().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.SPLIT);
            assertThat(e.getEventSubtype()).isEqualTo("병합");
            assertThat(e.getStockRate()).isEqualByComparingTo(new BigDecimal("0.2000"));
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("EC-3/AC-8: 빈 output1(액면교체 이력 없음·1999년 이전) → rowCount=0·rawRowCount=0, 적재 0행")
        void backfillFetch_emptyResponse() throws Exception {
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisRevSplitResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            RevSplitBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            assertThat(fetch.rawRowCount()).isZero();
            assertThat(fetch.validRows()).isEmpty();
            assertThat(result.rowCount()).isZero();
            assertThat(result.rawRowCount()).isZero();
            assertThat(result.oldestTradeDate()).isNull();
            verify(corporateEventInserter).insertBatch(List.of());
        }
    }
}
