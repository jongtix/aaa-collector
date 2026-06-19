package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasDailyOhlcvCollectionService 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-OHLCV-001)")
class OverseasDailyOhlcvCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 18);
    private static final String TODAY_YMD = "20260618";
    private static final String PREV_YMD = "20260617";

    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private OverseasDailyOhlcvCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new OverseasDailyOhlcvCollectionService(
                        stockRepository, dailyOhlcvRepository, batchRestExecutor, distributor);
    }

    private Stock stockOf(String symbol, Market market, AssetType assetType) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(market)
                .assetType(assetType)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    private KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row(
            String xymd, String price, String tvol, String tamt) {
        return new KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow(
                xymd, price, price, price, price, tvol, tamt);
    }

    private KisOverseasDailyOhlcvResponse response(
            String zdiv, List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> rows) {
        return new KisOverseasDailyOhlcvResponse(
                "0",
                "MCA00000",
                "정상",
                new KisOverseasDailyOhlcvResponse.Output1("DNASAAPL", zdiv, "100"),
                rows);
    }

    /** zdiv=4, 확정(직전 영업일) 정상 행 1건 응답. */
    private KisOverseasDailyOhlcvResponse validResponse() {
        return response("4", List.of(row(PREV_YMD, "296.9200", "42745060", "12697950974")));
    }

    private void stubExecute(
            KisAccountCredential cred, String symbol, KisOverseasDailyOhlcvResponse resp) {
        when(batchRestExecutor.execute(
                        eq(cred),
                        any(),
                        anyString(),
                        eq(KisOverseasDailyOhlcvResponse.class),
                        eq(symbol)))
                .thenReturn(BatchResult.success(resp));
    }

    @Nested
    @DisplayName("collect — 대상 조회 (AC-TGT-1, AC-PATH-4)")
    class Target {

        @Test
        @DisplayName("findAllActiveOverseasTradable() 진입점 호출 — 미국 STOCK+ETF 한정 조회")
        void collect_callsOverseasTradableQuery() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            service.collect(TODAY);

            verify(stockRepository).findAllActiveOverseasTradable();
        }

        @Test
        @DisplayName("빈 대상 — attempted=0, 외부 호출 0회 (AC-PATH-4, REQ-OVOH-030)")
        void collect_emptyTarget_zeroResultNoCall() {
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            CollectionResult result = service.collect(TODAY);

            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isZero();
            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("collect — EXCD 라우팅 (AC-PATH-2, REQ-OVOH-002)")
    class ExcdRouting {

        @Test
        @DisplayName("NASDAQ → EXCD=NAS")
        void collect_nasdaq_routesToNas() {
            assertExcd("AAPL", Market.NASDAQ, AssetType.STOCK, "NAS");
        }

        @Test
        @DisplayName("NYSE → EXCD=NYS")
        void collect_nyse_routesToNys() {
            assertExcd("F", Market.NYSE, AssetType.STOCK, "NYS");
        }

        @Test
        @DisplayName("AMEX(SPY ETF) → EXCD=AMS — NYSE Arca 실측 정합, 상품기본정보 529와 구분")
        void collect_amex_routesToAms() {
            assertExcd("SPY", Market.AMEX, AssetType.ETF, "AMS");
        }

        @SuppressWarnings("unchecked")
        private void assertExcd(
                String symbol, Market market, AssetType assetType, String expectedExcd) {
            // Arrange
            Stock stock = stockOf(symbol, market, assetType);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            stubExecute(ISA, symbol, validResponse());

            // Act
            service.collect(TODAY);

            // Assert
            verify(batchRestExecutor)
                    .execute(eq(ISA), uriCaptor.capture(), anyString(), any(), eq(symbol));
            assertThat(uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString())
                    .contains("EXCD=" + expectedExcd);
        }
    }

    @Nested
    @DisplayName("collect — dailyprice 요청 파라미터 (AC-SAVE-2, AC-SCOPE-1, REQ-OVOH-003a)")
    class RequestParameters {

        @Test
        @DisplayName("MODP=0(원주가) — 국내 FID_ORG_ADJ_PRC=1(원주가)와 의미 반대, 1 복사 금지")
        @SuppressWarnings("unchecked")
        void fetchBatch_sendsModp0_rawPrice() {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            uriCaptor.capture(),
                            eq("HHDFS76240000"),
                            eq(KisOverseasDailyOhlcvResponse.class),
                            eq("AAPL")))
                    .thenReturn(BatchResult.success(validResponse()));

            // Act
            service.collect(TODAY);

            // Assert
            String uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString();
            assertThat(uri).contains("MODP=0");
            assertThat(uri).doesNotContain("MODP=1");
            assertThat(uri).contains("GUBN=0");
            assertThat(uri).contains("SYMB=AAPL");
        }

        @Test
        @DisplayName("BYMD=실행 ET 거래일 단건 호출 — 종목당 execute 정확히 1회 (AC-SCOPE-1)")
        @SuppressWarnings("unchecked")
        void fetchBatch_bymdEtToday_singleCall() {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            ArgumentCaptor<Function<UriBuilder, URI>> uriCaptor =
                    ArgumentCaptor.forClass(Function.class);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            uriCaptor.capture(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class),
                            eq("AAPL")))
                    .thenReturn(BatchResult.success(validResponse()));

            // Act
            service.collect(TODAY);

            // Assert — 종목당 1회 호출, BYMD=ET 거래일
            verify(batchRestExecutor, times(1))
                    .execute(eq(ISA), any(), anyString(), any(), eq("AAPL"));
            String uri = uriCaptor.getValue().apply(UriComponentsBuilder.newInstance()).toString();
            assertThat(uri).contains("BYMD=" + TODAY_YMD);
        }
    }

    @Nested
    @DisplayName("collect — 멀티키 경로 / 모든 키 죽음 (AC-PATH-1, AC-PATH-3)")
    class MultiKeyPath {

        @Test
        @DisplayName("distributor 멀티키 분산 위임 — 집계 시도=2, 성공=2")
        void collect_delegatesToDistributor() {
            // Arrange
            Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));
            stubExecute(ISA, "AAPL", validResponse());
            stubExecute(GOLD, "F", validResponse());

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            verify(distributor).distribute(List.of(s1, s2));
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isZero();
        }

        @Nested
        @DisplayName("모든 키 죽음 (AC-PATH-3, REQ-OVOH-031)")
        class AllKeysDead {

            private Logger serviceLogger;
            private ListAppender<ILoggingEvent> listAppender;

            @BeforeEach
            void attachLogAppender() {
                serviceLogger =
                        (Logger) LoggerFactory.getLogger(OverseasDailyOhlcvCollectionService.class);
                listAppender = new ListAppender<>();
                listAppender.start();
                serviceLogger.addAppender(listAppender);
            }

            @AfterEach
            void detachLogAppender() {
                serviceLogger.detachAppender(listAppender);
                listAppender.stop();
            }

            @Test
            @DisplayName("빈 할당 — execute 0회, 전체 skip, ERROR 1회 (AC-PATH-3)")
            void collect_emptyAllocation_skipAllErrorOnce() {
                // Arrange
                Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
                Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
                List<Stock> stocks = List.of(s1, s2);
                when(stockRepository.findAllActiveOverseasTradable()).thenReturn(stocks);
                when(distributor.distribute(stocks)).thenReturn(Map.of());

                // Act
                CollectionResult result = service.collect(TODAY);

                // Assert
                verify(batchRestExecutor, never())
                        .execute(any(), any(), anyString(), any(), anyString());
                assertThat(result.attempted()).isEqualTo(2);
                assertThat(result.succeeded()).isZero();
                assertThat(result.skipped()).isEqualTo(2);
                List<ILoggingEvent> errorLogs =
                        listAppender.list.stream()
                                .filter(e -> e.getLevel() == Level.ERROR)
                                .toList();
                assertThat(errorLogs).hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("collect — 검증 skip (AC-VAL-1, REQ-OVOH-012)")
    class Validation {

        @Test
        @DisplayName("가격 0/음수 행 — 저장 제외")
        void collect_nonPositivePrice_notInserted() {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "0", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("가격 > USD 100,000 행 — 저장 제외(상한 가드)")
        void collect_priceAboveMax_notInserted() {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100001", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("tvol ≤ 0 행 — 저장 제외(양수 검증)")
        void collect_nonPositiveTvol_notInserted() {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "0", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("tamt ≤ 0 행 — 저장 제외(양수 검증)")
        void collect_nonPositiveTamt_notInserted() {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "100", "1000", "-5"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("숫자 파싱 실패 행 — 저장 제외")
        void collect_parseFailure_notInserted() {
            stubSingle("AAPL", response("4", List.of(row(PREV_YMD, "abc", "1000", "1000"))));

            service.collect(TODAY);

            verifyNoInsert();
        }

        @Test
        @DisplayName("회귀(MA-01): tamt=1.27e10(AAPL 실측) 정상 저장 — tvol/tamt 상한 미적용")
        void collect_largeTamt_inserted() {
            // Arrange — 국내 VOLUME_MAX(1.0e10)를 초과하는 정상 미국 대형주 거래대금
            stubSingle(
                    "AAPL",
                    response("4", List.of(row(PREV_YMD, "296.9200", "42745060", "12697950974"))));

            // Act
            service.collect(TODAY);

            // Assert — reject되지 않고 정상 저장
            verify(dailyOhlcvRepository, times(1))
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(42_745_060L),
                            eq(12_697_950_974L));
        }
    }

    @Nested
    @DisplayName("collect — zdiv 가드 (AC-VAL-4, REQ-OVOH-016)")
    class ZdivGuard {

        @Test
        @DisplayName("zdiv>4 — 종목 skip+WARN, 저장 0건")
        void collect_zdivAboveMax_skipsStock() {
            stubSingle("AAPL", response("5", List.of(row(PREV_YMD, "1.40000", "1000", "1000"))));

            CollectionResult result = service.collect(TODAY);

            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
        }

        @Test
        @DisplayName("zdiv=4 — 정상 저장")
        void collect_zdiv4_inserted() {
            stubSingle("AAPL", validResponse());

            CollectionResult result = service.collect(TODAY);

            verify(dailyOhlcvRepository, times(1))
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Long.class),
                            any(Long.class));
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("zdiv 비숫자(NumberFormatException) — 보수적 상한 초과 처리, 종목 skip")
        void collect_zdivNonNumeric_skipsStock() {
            // Arrange — "N/A"나 빈 문자열처럼 숫자 파싱 불가한 zdiv
            stubSingle("AAPL", response("N/A", List.of(row(PREV_YMD, "296.9200", "1000", "1000"))));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert — parseZdiv NFE 경로: ZDIV_MAX+1 반환 → zdiv>4 가드 발동 → skip
            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
        }
    }

    @Nested
    @DisplayName("collect — 빈응답 가드 (AC-VAL-5, REQ-OVOH-017)")
    class EmptyResponseGuard {

        @Test
        @DisplayName("rt_cd=0이나 output 빈값 — 종목 skip, 저장 0건")
        void collect_emptyOutput_skipsStock() {
            // Arrange — GSAT@AMS 실측: rt_cd=0 + 빈 output
            KisOverseasDailyOhlcvResponse empty =
                    new KisOverseasDailyOhlcvResponse("0", "MCA00000", "정상", null, List.of());
            stubSingle("AAPL", empty);

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            verifyNoInsert();
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — ET 당일 행 가드 (AC-VAL-3, REQ-OVOH-015)")
    class EtTodayRowGuard {

        @Test
        @DisplayName("최신 행 xymd==ET today — 그 행 제외, 직전 영업일 행은 저장(KST 비교 시 무력화 회귀)")
        void collect_etTodayRowExcluded_prevBusinessDaySaved() {
            // Arrange — 최신 행은 ET 당일(미마감 스냅샷 tvol 비정상), 직전 행은 확정
            KisOverseasDailyOhlcvResponse resp =
                    response(
                            "4",
                            List.of(
                                    row(TODAY_YMD, "296.9200", "78511", "23362105"),
                                    row(PREV_YMD, "295.9500", "42745060", "12697950974")));
            stubSingle("AAPL", resp);

            // Act
            service.collect(TODAY);

            // Assert — ET 당일 행(tvol=78511) 미저장, 직전 영업일 행(tvol=42745060)만 저장
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            eq(LocalDate.of(2026, 6, 18)),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Long.class),
                            any(Long.class));
            verify(dailyOhlcvRepository, times(1))
                    .insertIgnoreDuplicate(
                            any(),
                            eq(LocalDate.of(2026, 6, 17)),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq(42_745_060L),
                            eq(12_697_950_974L));
        }
    }

    @Nested
    @DisplayName("collect — 토큰 실패 / 집계 (AC-VAL-2, AC-AGG-1)")
    class TokenFailureAndAggregation {

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패")
        void collect_tokenIssue_gracefulSkip() {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class),
                            eq("AAPL")))
                    .thenThrow(new KisTokenIssueException("isa", new RuntimeException("token")));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("BatchResult.skip — skip 집계")
        void collect_batchSkip_countedSkipped() {
            // Arrange
            Stock stock = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class),
                            eq("AAPL")))
                    .thenReturn(BatchResult.skip("AAPL", "EGW00201 소진"));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("일부 성공·일부 skip — attempted=succeeded+skipped (AC-AGG-1)")
        void collect_partialSuccess_aggregates() {
            // Arrange
            Stock s1 = stockOf("AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock s2 = stockOf("F", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));
            stubExecute(ISA, "AAPL", validResponse());
            when(batchRestExecutor.execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisOverseasDailyOhlcvResponse.class),
                            eq("F")))
                    .thenReturn(BatchResult.skip("F", "skip"));

            // Act
            CollectionResult result = service.collect(TODAY);

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded() + result.skipped()).isEqualTo(result.attempted());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ── helpers ──

    private void stubSingle(String symbol, KisOverseasDailyOhlcvResponse resp) {
        Stock stock = stockOf(symbol, Market.NASDAQ, AssetType.STOCK);
        lenient().when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
        lenient()
                .when(distributor.distribute(List.of(stock)))
                .thenReturn(Map.of(ISA, List.of(stock)));
        lenient()
                .when(
                        batchRestExecutor.execute(
                                eq(ISA),
                                any(),
                                anyString(),
                                eq(KisOverseasDailyOhlcvResponse.class),
                                eq(symbol)))
                .thenReturn(BatchResult.success(resp));
    }

    private void verifyNoInsert() {
        verify(dailyOhlcvRepository, never())
                .insertIgnoreDuplicate(
                        any(), any(), any(), any(), any(), any(), any(Long.class), any(Long.class));
    }
}
