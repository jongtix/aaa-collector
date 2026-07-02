package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.observability.RowFailureHandler;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.sql.SQLException;
import java.util.List;
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

/**
 * SPEC-COLLECTOR-KISGATE-001 M6(T19) — {@link DividendScheduleCollectionService} 게이트 경유 단위 테스트.
 *
 * <p>패턴 A는 Behavior:Changed이므로 신규 게이트 라우팅(throttle-on 4-arg 경유·CTS 페이징 전체 = 1 batch이므로 세션 1회 open)과
 * 기존 수집 의미(비관심종목 skip·검증 skip·CTS 페이징·멱등·독성 행 흡수)를 함께 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DividendScheduleCollectionService — 게이트 경유 단위 테스트")
class DividendScheduleCollectionServiceTest {

    private static final String TR_ID = "HHKDB669102C0";

    @Mock private GuardedKisExecutor guardedKisExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private StockRepository stockRepository;
    @Mock private CorporateEventRepository corporateEventRepository;
    @Mock private CorporateEventInserter corporateEventInserter;
    @Mock private LeaseSession session;

    @Captor private ArgumentCaptor<List<CorporateEvent>> inserterCaptor;

    private DividendScheduleCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new DividendScheduleCollectionService(
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

    private KisDividendScheduleResponse.DividendRow sampleRow(String shtCd) {
        return new KisDividendScheduleResponse.DividendRow(
                shtCd,
                "20260613", // record_date
                "결산배당", // divi_kind
                "500", // per_sto_divi_amt
                "2.50", // divi_rate
                "0.00", // stk_divi_rate
                "20260630", // divi_pay_dt
                "", // stk_div_pay_dt
                "", // odd_pay_dt
                "5000", // face_val
                "보통주", // stk_kind
                "0"); // high_divi_gb
    }

    private KisDividendScheduleResponse singlePageResponse(
            List<KisDividendScheduleResponse.DividendRow> rows) {
        // CTS = null → 마지막 페이지
        return new KisDividendScheduleResponse("0", "MCA00000", "정상처리", rows, null);
    }

    private KisDividendScheduleResponse pageWithCts(
            List<KisDividendScheduleResponse.DividendRow> rows, String nextCts) {
        return new KisDividendScheduleResponse(
                "0", "MCA00000", "정상처리", rows, new KisDividendScheduleResponse.CtsPaging(nextCts));
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — 관심종목 → DIVIDEND 저장")
    class HappyPath {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // 매핑 필드 전체 검증을 한 테스트에서 수행
        @DisplayName("관심종목 행 → CorporateEvent(DIVIDEND) 저장, 매핑 정확")
        void watchlistRow_storedAsDividend() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(sampleRow("005930"))));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isZero();
            assertThat(result.skippedValidation()).isZero();

            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());

            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getEventType()).isEqualTo(EventType.DIVIDEND);
            assertThat(saved.getStock()).isSameAs(stock);
            assertThat(saved.getEventDate()).hasYear(2026).hasMonthValue(6).hasDayOfMonth(13);
            assertThat(saved.getEventSubtype()).isEqualTo("결산배당");
            assertThat(saved.getCashAmount()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("pay_date·stock_pay_date·face_value 매핑 정확")
        void dateAndAmountFieldsCorrect() throws Exception {
            // Arrange
            Stock stock = watchlistStock("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(sampleRow("000660"))));

            // Act
            service.collect("20260601", "20260630");

            // Assert
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());

            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getPayDate()).hasYear(2026).hasMonthValue(6).hasDayOfMonth(30);
            assertThat(saved.getFaceValue()).isEqualTo(5000L);
            assertThat(saved.getHighDividendFlag()).isEqualTo("0");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 비관심종목 skip (REQ-BATCH3-052)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("비관심종목 skip (REQ-BATCH3-052)")
    class NonWatchlistSkip {

        @Test
        @DisplayName("비관심종목 행은 skip, 관심종목만 저장")
        void nonWatchlistRows_skipped() throws Exception {
            // Arrange — 관심종목: 005930, 비관심종목: 000001 (2건)
            Stock watchlistStock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(watchlistStock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            singlePageResponse(
                                    List.of(
                                            sampleRow("005930"), // 관심종목
                                            sampleRow("000001"), // 비관심종목
                                            sampleRow("000002")))); // 비관심종목

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skippedNonWatchlist()).isEqualTo(2);
            verify(corporateEventInserter, times(1)).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("관심종목이 없으면 전부 skip — 저장 없음")
        void noWatchlistMatches_nothingStored() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            singlePageResponse(List.of(sampleRow("005930"), sampleRow("000660"))));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.skippedNonWatchlist()).isEqualTo(2);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // CTS 페이징
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CTS 페이징 — 전체 순회 후 종료")
    class CtsPaging {

        @Test
        @DisplayName("CTS 2페이지 순회 → 양쪽 관심종목 저장")
        void multiPage_traversesAll() throws Exception {
            // Arrange
            Stock stock1 = watchlistStock("005930");
            Stock stock2 = watchlistStock("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock1, stock2));

            // Page 1: CTS 있음 → 다음 페이지
            KisDividendScheduleResponse page1 =
                    pageWithCts(List.of(sampleRow("005930")), "NEXT_PAGE_TOKEN");
            // Page 2: CTS null → 마지막
            KisDividendScheduleResponse page2 = singlePageResponse(List.of(sampleRow("000660")));

            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(page1)
                    .thenReturn(page2);

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — 2건 저장
            assertThat(result.succeeded()).isEqualTo(2);
            verify(corporateEventInserter, times(2)).insertBatchIsolated(any(), any());
            // 게이트 2회 경유 확인(2페이지), 동일 세션 공유 + per-batch 스냅샷 1회
            verify(guardedKisExecutor, times(2))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class));
            verify(keyLeaseRegistry, times(1)).openSession();
        }

        @Test
        @DisplayName("첫 페이지 빈 output1 → 즉시 종료, 저장 없음")
        void emptyFirstPage_zeroSuccess() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of()));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.succeeded()).isZero();
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 검증 실패 skip (REQ-BATCH3-070)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("검증 실패 skip (REQ-BATCH3-070)")
    class ValidationSkip {

        @Test
        @DisplayName("record_date null 행은 skip")
        void nullRecordDate_skip() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            KisDividendScheduleResponse.DividendRow nullDateRow =
                    new KisDividendScheduleResponse.DividendRow(
                            "005930",
                            null,
                            "결산배당",
                            "500",
                            "2.50",
                            "0.00",
                            "20260630",
                            "",
                            "",
                            "5000",
                            "보통주",
                            "0");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(nullDateRow)));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("shtCd null 행은 skip")
        void nullShtCd_skip() throws Exception {
            // Arrange
            when(stockRepository.findAllActive()).thenReturn(List.of());
            KisDividendScheduleResponse.DividendRow nullShtCd =
                    new KisDividendScheduleResponse.DividendRow(
                            null,
                            "20260613",
                            "결산배당",
                            "500",
                            "2.50",
                            "0.00",
                            "20260630",
                            "",
                            "",
                            "5000",
                            "보통주",
                            "0");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(nullShtCd)));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.skippedValidation()).isEqualTo(1);
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("멱등 저장 — uk_corporate_events")
    class Idempotency {

        @Test
        @DisplayName("동일 행 재수집 시 insertIgnoreDuplicate 재호출 (DB가 중복 무시)")
        void idempotentRerun() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(sampleRow("005930"))));

            // Act — 2회 실행
            service.collect("20260601", "20260630");
            service.collect("20260601", "20260630");

            // Assert
            verify(corporateEventInserter, times(2)).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // parseDateOrNull 파싱 실패 경로 (FIX 4)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseDateOrNull — 유효하지 않은 날짜 파싱 실패 (FIX 4)")
    class ParseDateOrNull {

        @Test
        @DisplayName("8자리지만 유효하지 않은 날짜 — 파싱 실패 시 pay_date=null로 저장 성공")
        void invalidDate_parseFailure_nullPayDate() throws Exception {
            // Arrange — diviPayDt에 월=99인 유효하지 않은 날짜 전달
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            KisDividendScheduleResponse.DividendRow rowWithBadDate =
                    new KisDividendScheduleResponse.DividendRow(
                            "005930",
                            "20260613",
                            "결산배당",
                            "500",
                            "2.50",
                            "0.00",
                            "20269900", // diviPayDt: 8자리지만 월=99(유효하지 않음)
                            "",
                            "",
                            "5000",
                            "보통주",
                            "0");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(rowWithBadDate)));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — 저장 성공, pay_date=null
            assertThat(result.succeeded()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedNullPayDate =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedNullPayDate.getPayDate()).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // parseRateOrNull — DECIMAL(12,4) 경계 초과 skip (FIX 4)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseRateOrNull — DECIMAL(12,4) 정수부 8자리 초과 skip (FIX 4)")
    class ParseRateOrNull {

        @Test
        @DisplayName("배당률 정수부 9자리 — DECIMAL(12,4) 초과 → cash_rate=null로 저장 (경계 초과 시 null 매핑)")
        void rateExceedsDecimalBound_cashRateNull() throws Exception {
            // Arrange — 정수부 9자리(100000000.0) → DECIMAL(12,4) 정수부 8자리(99999999) 초과
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            KisDividendScheduleResponse.DividendRow rowWithBigRate =
                    new KisDividendScheduleResponse.DividendRow(
                            "005930",
                            "20260613",
                            "결산배당",
                            "500",
                            "100000000.0", // cash_rate: 정수부 9자리 초과 → null
                            "0.00",
                            "",
                            "",
                            "",
                            "5000",
                            "보통주",
                            "0");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(singlePageResponse(List.of(rowWithBigRate)));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — 저장 성공, cash_rate=null (parseRateOrNull이 경계 초과 시 null 반환)
            assertThat(result.succeeded()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent savedNullRate =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(savedNullRate.getCashRate()).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 독성 행 skip — 영구 정체 방지 (W1)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("독성 행 skip — insertIgnoreDuplicate 예외 시 처리 계속 (W1)")
    class PoisonRowSkip {

        @Test
        @DisplayName("1건 삽입 예외 → 나머지 행 저장, 루프 종료")
        void oneRowThrows_otherRowsInserted_processingContinues() throws Exception {
            // Arrange — 3건: 005930(독성), 000660(정상), 035420(정상)
            Stock stock1 = watchlistStock("005930");
            Stock stock2 = watchlistStock("000660");
            Stock stock3 = watchlistStock("035420");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock1, stock2, stock3));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            singlePageResponse(
                                    List.of(
                                            sampleRow("005930"), // 독성 행
                                            sampleRow("000660"), // 정상
                                            sampleRow("035420")))); // 정상

            // REQ-INSERT-011: insertBatchIsolated — 005930 독성 행에 대해 콜백 호출 시뮬레이션
            doAnswer(
                            invocation -> {
                                List<CorporateEvent> rows = invocation.getArgument(0);
                                @SuppressWarnings("unchecked")
                                RowFailureHandler<CorporateEvent> handler =
                                        invocation.getArgument(1);
                                // 005930(독성 행)에 대해서만 콜백 호출
                                final String toxicSymbol = "005930";
                                final SQLException toxicEx =
                                        new SQLException("Data too long", "22001", 1406);
                                for (CorporateEvent entity : rows) {
                                    if (toxicSymbol.equals(entity.getStock().getSymbol())) {
                                        handler.onFailure(entity, toxicEx);
                                    }
                                }
                                return null;
                            })
                    .when(corporateEventInserter)
                    .insertBatchIsolated(any(), any());

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            // (a) insertBatchIsolated 1회 (3건 배치), 정상 행 2건 성공
            verify(corporateEventInserter, times(1)).insertBatchIsolated(any(), any());
            assertThat(result.succeeded()).isEqualTo(2);
            // (b) 독성 행은 skippedValidation 집계
            assertThat(result.skippedValidation()).isEqualTo(1);
            // (c) 게이트: 1페이지만 경유 (루프 종료)
            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // RIGHTS_ISSUE 미수집 (REQ-BATCH3-054)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EventType.DIVIDEND만 수집 — RIGHTS_ISSUE 미수집")
    class DividendOnly {

        @Test
        @DisplayName("저장된 모든 CorporateEvent가 DIVIDEND 타입")
        void allStoredEventsAreDividend() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            singlePageResponse(List.of(sampleRow("005930"), sampleRow("005930"))));

            // Act
            service.collect("20260601", "20260630");

            // Assert
            verify(corporateEventInserter, atLeastOnce())
                    .insertBatchIsolated(inserterCaptor.capture(), any());
            assertThat(inserterCaptor.getAllValues())
                    .flatMap(l -> l)
                    .allMatch(e -> e.getEventType() == EventType.DIVIDEND);
        }
    }
}
