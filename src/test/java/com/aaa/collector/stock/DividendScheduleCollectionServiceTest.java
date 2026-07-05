package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.observability.RowFailureHandler;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * DividendScheduleCollectionService 단위 테스트 (SPEC-COLLECTOR-DIVIDEND-FIX-001 T2/T3).
 *
 * <p>종목별 루프 전환(REQ-DIVFIX-010~012)·Virtual Thread 병렬(REQ-DIVFIX-013)·전 키 사망 단락(REQ-DIVFIX-040)· 종목별
 * 예외 격리(REQ-DIVFIX-041)를 검증한다. CTS 페이징은 제거되었으므로 관련 테스트는 존재하지 않는다. 검증·미확정(0/0) defer 판정 세부 로직(단위
 * 수준)은 {@link DividendRowAccumulatorTest}가 커버하므로, 본 테스트는 서비스가 그 협력자를 올바르게 배선했는지(종목별 batch 흐름·최종 집계)
 * end-to-end로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DividendScheduleCollectionService — 종목별 루프 단위 테스트")
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
        lenient().when(keyLeaseRegistry.openSession()).thenReturn(session);
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

    private KisDividendScheduleResponse response(
            List<KisDividendScheduleResponse.DividendRow> rows) {
        return new KisDividendScheduleResponse("0", "MCA00000", "정상처리", rows);
    }

    // ────────────────────────────────────────────────────────────────────
    // 대상 조회 / 세션 (REQ-DIVFIX-010, REQ-DIVFIX-040)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 대상 조회 / 세션")
    class Target {

        @Test
        @DisplayName("findAllActiveDomesticTradable() 진입점 호출 — 전체조회(SHT_CD=\"\") 사용 안 함")
        void collect_callsDomesticTradableQuery() {
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of());

            service.collect("20260601", "20260630");

            verify(stockRepository).findAllActiveDomesticTradable();
        }

        @Test
        @DisplayName("빈 대상 — 게이트·세션 미호출, 0건")
        void collect_emptyTarget_noGateCall() {
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of());

            DividendCollectionResult result = service.collect("20260601", "20260630");

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(keyLeaseRegistry, never()).openSession();
        }

        @Test
        @DisplayName("전 키 사망(빈 스냅샷) — per-stock 수집 0회, 전체 skip")
        void collect_allKeysDead_skipAll() throws Exception {
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(session.isEmpty()).thenReturn(true);

            DividendCollectionResult result = service.collect("20260601", "20260630");

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            verify(guardedKisExecutor, never())
                    .execute(any(LeaseSession.class), any(), anyString(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 요청 파라미터 (REQ-DIVFIX-011)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 요청 파라미터 (REQ-DIVFIX-011)")
    class RequestParams {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("SHT_CD=종목코드·GB1=0·F_DT/T_DT·CTS=공백·HIGH_GB=공백으로 게이트 호출")
        void collect_buildsPerStockRequest() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(guardedKisExecutor.execute(
                            eq(session),
                            uriCaptor.capture(),
                            eq(TR_ID),
                            eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of()));

            // Act
            service.collect("20260601", "20260630");

            // Assert
            URI uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance());
            assertThat(uri.toString())
                    .contains("/uapi/domestic-stock/v1/ksdinfo/dividend")
                    .contains("SHT_CD=005930")
                    .contains("GB1=0")
                    .contains("F_DT=20260601")
                    .contains("T_DT=20260630")
                    .contains("CTS=")
                    .contains("HIGH_GB=");
        }

        @Test
        @DisplayName("TR ID = HHKDB669102C0")
        void collect_usesCorrectTrId() throws Exception {
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of()));

            service.collect("20260601", "20260630");

            verify(guardedKisExecutor)
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class));
        }

        @Test
        @DisplayName("종목당 KIS 호출 1회 — CTS 재호출 없음")
        void collect_callsGatewayOncePerStock() throws Exception {
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("005930"))));

            service.collect("20260601", "20260630");

            verify(guardedKisExecutor, times(1))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 — 종목별 조회 → DIVIDEND 저장")
    class HappyPath {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // 매핑 필드 전체 검증을 한 테스트에서 수행
        @DisplayName("종목 배당 행 → CorporateEvent(DIVIDEND) 저장, 매핑 정확")
        void stockRow_storedAsDividend() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("005930"))));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
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
            assertThat(saved.getCurrencyCode()).isEqualTo("KRW");
        }

        @Test
        @DisplayName("pay_date·stock_pay_date·face_value 매핑 정확")
        void dateAndAmountFieldsCorrect() throws Exception {
            // Arrange
            Stock stock = watchlistStock("000660");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("000660"))));

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

        @Test
        @DisplayName("빈 output1 — 해당 종목 저장 없음")
        void emptyOutput1_nothingStored() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of()));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.succeeded()).isZero();
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 미확정(0/0) 배당 행 defer — end-to-end 배선 (REQ-DIVFIX-020~022, RD-6/RD-7)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 미확정(0/0) 배당 defer 배선 (T3, REQ-DIVFIX-020~022)")
    class UnconfirmedDividendDefer {

        private KisDividendScheduleResponse.DividendRow zeroRow(String shtCd, String recordDate) {
            return new KisDividendScheduleResponse.DividendRow(
                    shtCd,
                    recordDate,
                    "결산배당",
                    "0", // per_sto_divi_amt
                    "0.00", // divi_rate
                    "0.00",
                    "",
                    "",
                    "",
                    "5000",
                    "보통주",
                    "0");
        }

        @Test
        @DisplayName("0/0 행 — 저장되지 않는다(succeeded=0), 독성/검증 카운터로 오분류되지 않음")
        void zeroRow_notPersisted() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(zeroRow("005930", "20260613"))));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — defer는 attempted에는 잡히나(전체 output1 행) 저장되지 않고 skippedValidation에도 잡히지 않는다
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skippedValidation()).isZero();
            verify(corporateEventInserter, never()).insertBatchIsolated(any(), any());
        }

        @Test
        @DisplayName("확정 행 + 0/0 행 혼재 — 확정 행만 저장, 0/0 행은 defer")
        void mixedConfirmedAndZeroRows_onlyConfirmedPersisted() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            response(
                                    List.of(
                                            sampleRow("005930"), // 확정 행(amt=500, rate=2.50)
                                            zeroRow("005930", "20260613")))); // 0/0 defer 행

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            assertThat(inserterCaptor.getAllValues().stream().flatMap(List::stream).toList())
                    .hasSize(1);
        }

        @Test
        @DisplayName("rate-only 행(amt=0, rate!=0) — defer 아님, 원본 그대로 저장(역산 없음)")
        void rateOnlyRow_storedAsIs() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            KisDividendScheduleResponse.DividendRow rateOnlyRow =
                    new KisDividendScheduleResponse.DividendRow(
                            "005930",
                            "20260613",
                            "결산배당",
                            "0", // per_sto_divi_amt=0
                            "2.50", // divi_rate!=0 → rate-only, defer 대상 아님(RD-7)
                            "0.00",
                            "",
                            "",
                            "",
                            "5000",
                            "보통주",
                            "0");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(rateOnlyRow)));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — 저장됨, cash_amount는 파싱값 0 그대로(역산 없음)
            assertThat(result.succeeded()).isEqualTo(1);
            verify(corporateEventInserter).insertBatchIsolated(inserterCaptor.capture(), any());
            CorporateEvent saved =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getCashAmount()).isEqualByComparingTo("0");
            assertThat(saved.getCashRate()).isEqualByComparingTo("2.5000");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Virtual Thread 병렬 (REQ-DIVFIX-013)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — Virtual Thread 병렬 (REQ-DIVFIX-013)")
    class ParallelCollection {

        @Test
        @DisplayName("다종목 — 모든 종목이 동일 세션으로 게이트 호출, 각각 저장")
        void collect_multipleStocks_allProcessed() throws Exception {
            // Arrange
            Stock stock1 = watchlistStock("005930");
            Stock stock2 = watchlistStock("000660");
            Stock stock3 = watchlistStock("035420");
            List<Stock> stocks = List.of(stock1, stock2, stock3);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(stocks);
            lenient()
                    .when(
                            guardedKisExecutor.execute(
                                    eq(session),
                                    any(),
                                    eq(TR_ID),
                                    eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("005930"))));

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert
            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(3);
            verify(guardedKisExecutor, times(3))
                    .execute(eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 종목별 예외 격리 (REQ-DIVFIX-041)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("collect — 종목별 예외 격리 (REQ-DIVFIX-041)")
    class PerStockExceptionIsolation {

        @Test
        @DisplayName("한 종목 인터럽트 — 나머지 종목은 계속 수집")
        void oneStockInterrupted_othersContinue() throws Exception {
            // Arrange
            Stock failing = watchlistStock("005930");
            Stock ok = watchlistStock("000660");
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(failing, ok));

            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenAnswer(
                            invocation -> {
                                Function<UriBuilder, URI> uriCustomizer = invocation.getArgument(1);
                                URI uri = uriCustomizer.apply(UriComponentsBuilder.newInstance());
                                if (uri.toString().contains("SHT_CD=005930")) {
                                    throw new InterruptedException("boom");
                                }
                                return response(List.of(sampleRow("000660")));
                            });

            // Act
            DividendCollectionResult result = service.collect("20260601", "20260630");

            // Assert — 005930은 skip, 000660만 저장
            assertThat(result.succeeded()).isEqualTo(1);
            verify(corporateEventInserter, times(1)).insertBatchIsolated(any(), any());
            // 인터럽트 플래그 복원(스레드 풀 회수 전 상태 확인 불가하므로 결과만 검증)
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
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
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
                    .thenReturn(response(List.of(nullDateRow)));

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
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("005930"))));

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
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
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
                    .thenReturn(response(List.of(rowWithBadDate)));

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
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
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
                    .thenReturn(response(List.of(rowWithBigRate)));

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
        @DisplayName("1개 종목 삽입 예외 → 나머지 종목은 저장, 전체 처리 계속")
        void oneStockToxic_othersInserted_processingContinues() throws Exception {
            // Arrange — 3개 종목: 005930(독성), 000660(정상), 035420(정상)
            Stock stock1 = watchlistStock("005930");
            Stock stock2 = watchlistStock("000660");
            Stock stock3 = watchlistStock("035420");
            when(stockRepository.findAllActiveDomesticTradable())
                    .thenReturn(List.of(stock1, stock2, stock3));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenAnswer(
                            invocation -> {
                                Function<UriBuilder, URI> uriCustomizer = invocation.getArgument(1);
                                URI uri = uriCustomizer.apply(UriComponentsBuilder.newInstance());
                                String symbol =
                                        uri.toString().contains("SHT_CD=005930")
                                                ? "005930"
                                                : uri.toString().contains("SHT_CD=000660")
                                                        ? "000660"
                                                        : "035420";
                                return response(List.of(sampleRow(symbol)));
                            });

            // REQ-INSERT-011: insertBatchIsolated — 005930 독성 행에 대해 콜백 호출 시뮬레이션
            doAnswer(
                            invocation -> {
                                List<CorporateEvent> rows = invocation.getArgument(0);
                                @SuppressWarnings("unchecked")
                                RowFailureHandler<CorporateEvent> handler =
                                        invocation.getArgument(1);
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
            // (a) insertBatchIsolated 3회(종목별 배치), 정상 종목 2건 성공
            verify(corporateEventInserter, times(3)).insertBatchIsolated(any(), any());
            assertThat(result.succeeded()).isEqualTo(2);
            // (b) 독성 행은 skippedValidation 집계
            assertThat(result.skippedValidation()).isEqualTo(1);
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
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(stock));
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(sampleRow("005930"), sampleRow("005930"))));

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

    // ────────────────────────────────────────────────────────────────────
    // SPEC-COLLECTOR-BACKFILL-009 W2 — 종목지정 전기간 배당 백필 fetch/persist
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BACKFILL-009: 종목지정 배당 백필 fetch/persist (REQ-BACKFILL-124~135)")
    class BackfillFetchPersist {

        private final LocalDate floor = LocalDate.of(1950, 1, 1);
        private final LocalDate today = LocalDate.of(2026, 6, 28);

        /** rate-only 행: amt=0·rate=400.00(액면가 대비 %), face_val=5000 — 저장 대상(RD-7), 역산 미수행(RD-3). */
        private KisDividendScheduleResponse.DividendRow rateOnlyRow(
                String shtCd, String recordDate) {
            return new KisDividendScheduleResponse.DividendRow(
                    shtCd, recordDate, "결산", "0", "400.00", "0.00", "", "", "", "5000", "보통주", "0");
        }

        /** 0/0 무배당 기준일 행: amt=0·rate=0.00 — 무조건 defer(RD-6). */
        private KisDividendScheduleResponse.DividendRow zeroZeroRow(
                String shtCd, String recordDate) {
            return new KisDividendScheduleResponse.DividendRow(
                    shtCd, recordDate, "결산", "0", "0.00", "0.00", "", "", "", "5000", "보통주", "0");
        }

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
        @DisplayName("AC-1: SHT_CD=종목코드·GB1=0·F_DT=19500101·T_DT=today 호출 파라미터 (전체조회·전기간 윈도우)")
        void backfillFetch_callsWithTickerSpecifiedFullHistoryParams() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of()));

            // Act
            service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert — 종목지정 + 전기간 윈도우, 전체조회(SHT_CD 공백) 아님
            URI uri = capturedUri();
            assertThat(uri.getRawQuery())
                    .contains("SHT_CD=005930")
                    .contains("GB1=0")
                    .contains("F_DT=19500101")
                    .contains("T_DT=20260628");
            assertThat(uri.getRawQuery()).doesNotContain("SHT_CD=&");
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-3/AC-8: rate-only 행(amt=0·rate=400.00) → 저장, cash_amount=0 원본·역산(20000) 미수행")
        void backfillFetch_rateOnlyRow_storedRawNoBackCalc() throws Exception {
            // Arrange — 삼성전자 2015 결산 재현: face=5000·rate=400.00·amt=0
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(rateOnlyRow("005930", "20150331"))));

            // Act
            DividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert — defer 아님, 원본 저장
            assertThat(fetch.validRows()).hasSize(1);
            CorporateEvent e = fetch.validRows().getFirst();
            assertThat(e.getEventType()).isEqualTo(EventType.DIVIDEND);
            assertThat(e.getEventSubtype()).isEqualTo("결산");
            assertThat(e.getEventDate()).isEqualTo(LocalDate.of(2015, 3, 31));
            assertThat(e.getFaceValue()).isEqualTo(5000L);
            assertThat(e.getCashRate()).isEqualByComparingTo("400.0000");
            // RD-3: cash_amount는 파싱 원본값 0 — face_val × divi_rate / 100 = 20000 역산 미수행
            assertThat(e.getCashAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("AC-2: 0/0 행은 defer(validRows 미포함), 단 rawRowCount에는 원본 행수로 집계")
        void backfillFetch_zeroZeroRow_deferredButCountedInRaw() throws Exception {
            // Arrange — 확정 rate-only 1건 + 0/0 무배당 1건
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(
                            response(
                                    List.of(
                                            rateOnlyRow("005930", "20150331"),
                                            zeroZeroRow("005930", "19991231"))));

            // Act
            DividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert — 종료 입력 rawRowCount=원본 2, 저장 1(0/0 defer)
            assertThat(fetch.rawRowCount()).isEqualTo(2);
            assertThat(fetch.validRows()).hasSize(1);
            assertThat(fetch.validRows().getFirst().getEventDate())
                    .isEqualTo(LocalDate.of(2015, 3, 31));
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName(
                "AC-4: 단일 rate-only 저장 → rowCount=1·rawRowCount=1·oldest=2015-03-31, insertBatch 1행")
        void backfillPersist_singleValidRow() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of(rateOnlyRow("005930", "20150331"))));

            // Act
            DividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            // Assert
            assertThat(fetch.rawRowCount()).isEqualTo(1);
            assertThat(result.rowCount()).isEqualTo(1);
            assertThat(result.rawRowCount()).isEqualTo(1);
            assertThat(result.oldestTradeDate()).isEqualTo(LocalDate.of(2015, 3, 31));
            verify(corporateEventInserter).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("EC/AC-9a: 100행 캡 도달 → rawRowCount=100(GROUP_A 종료 조건 미충족), fetch 정상 반환")
        void backfillFetch_hundredRowCap_rawRowCountReflectsCap() throws Exception {
            // Arrange — 100개 rate-only 행(전부 서로 다른 record_date)
            Stock stock = watchlistStock("005930");
            List<KisDividendScheduleResponse.DividendRow> rows =
                    IntStream.range(0, 100)
                            .mapToObj(
                                    i -> rateOnlyRow("005930", String.format("2015%04d", 1000 + i)))
                            .toList();
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(rows));

            // Act
            DividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);

            // Assert — rawRowCount=100 → GROUP_A 종료 조건("100행 미만") 미충족 → 이월(경고 로그, 분할 미구현 RD-2)
            assertThat(fetch.rawRowCount()).isEqualTo(100);
        }

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
        @DisplayName("EC: 빈 output1(무배당·1999년 이전) → rawRowCount=0·validRows 비어있음, persist 0행")
        void backfillFetch_emptyResponse() throws Exception {
            // Arrange
            Stock stock = watchlistStock("005930");
            when(guardedKisExecutor.execute(
                            eq(session), any(), eq(TR_ID), eq(KisDividendScheduleResponse.class)))
                    .thenReturn(response(List.of()));

            // Act
            DividendBackfillFetch fetch =
                    service.fetchWindowForBackfill(stock, session, floor, today);
            BackfillWindowResult result = service.persistWindowForBackfill(fetch);

            // Assert
            assertThat(fetch.rawRowCount()).isZero();
            assertThat(fetch.validRows()).isEmpty();
            assertThat(result.rowCount()).isZero();
            assertThat(result.rawRowCount()).isZero();
            assertThat(result.oldestTradeDate()).isNull();
            verify(corporateEventInserter).insertBatch(List.of());
        }
    }
}
