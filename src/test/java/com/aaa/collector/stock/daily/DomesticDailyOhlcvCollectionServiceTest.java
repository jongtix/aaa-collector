package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomesticDailyOhlcvCollectionService 단위 테스트")
class DomesticDailyOhlcvCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");
    private static final KisAccountCredential PENSION =
            new KisAccountCredential("pension", "33333333", "appkey-pension", "appsecret-pension");
    private static final KisAccountCredential STOCK_KEY =
            new KisAccountCredential("stock", "44444444", "appkey-stock", "appsecret-stock");
    private static final KisAccountCredential DC =
            new KisAccountCredential("dc", "55555555", "appkey-dc", "appsecret-dc");

    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private BatchRestExecutor batchRestExecutor;
    @Mock private HealthyKeyRoundRobinDistributor distributor;

    private DomesticDailyOhlcvCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new DomesticDailyOhlcvCollectionService(
                        stockRepository, dailyOhlcvRepository, batchRestExecutor, distributor);
    }

    /**
     * 단위 테스트용 Stock 생성. id는 null (JPA IDENTITY 미할당).
     *
     * <p>insertIgnoreDuplicate 호출 검증은 모두 any() 매처를 사용하므로 null id로 동작한다.
     */
    private Stock stockOf(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("테스트_" + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    private KisDailyOhlcvResponse.DailyOhlcvRow validRow(String date) {
        return new KisDailyOhlcvResponse.DailyOhlcvRow(
                date, "75000", "74000", "76000", "73000", "1000000", "75000000000", "N");
    }

    // Helper: build KisDailyOhlcvResponse with given rows
    private KisDailyOhlcvResponse stubResponse(List<KisDailyOhlcvResponse.DailyOhlcvRow> rows) {
        return new KisDailyOhlcvResponse("0", "MCA00000", "조회되었습니다.", rows);
    }

    @Nested
    @DisplayName("collect — 성공 경로")
    class CollectSuccess {

        @Test
        @DisplayName("활성 종목 1개 — 수집 성공, 시도=1, 성공=1, skip=0 (AC-5)")
        void collect_oneActiveStock_successResult() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            KisDailyOhlcvResponse.DailyOhlcvRow row = validRow("20260605");
            KisDailyOhlcvResponse response = stubResponse(List.of(row));
            BatchResult<KisDailyOhlcvResponse> batchResult = BatchResult.success(response);
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(batchResult);

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
        }

        @Test
        @DisplayName("활성 종목 없음 — 시도=0, 성공=0, skip=0")
        void collect_noActiveStocks_zeroResult() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            assertThat(result.attempted()).isEqualTo(0);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(0);
            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("활성 종목 2개, 건강 키 2개 — 결과 집계 시도=2, 성공=2, skip=0 (AC-5)")
        void collect_twoActiveStocks_aggregatesCorrectly() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));

            KisDailyOhlcvResponse r1 = stubResponse(List.of(validRow("20260605")));
            KisDailyOhlcvResponse r2 = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(r1));
            when(batchRestExecutor.execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660")))
                    .thenReturn(BatchResult.success(r2));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("collect — skip 집계 (REQ-BATCH-023, -025, -026)")
    class CollectSkip {

        @Test
        @DisplayName("BatchResult.skip 반환 시 — skip 집계에 포함, 성공 미집계")
        void collect_batchSkip_countedInSkipped() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930", "테스트 skip"));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패 (REQ-BATCH-025, AC-4)")
        void collect_tokenIssueException_gracefulSkip() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenThrow(
                            new KisTokenIssueException("isa", new RuntimeException("token fail")));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("종목 2개 중 1개 skip — 시도=2, 성공=1, skip=1 (AC-4)")
        void collect_oneSkipOneSuccess_correctMeta() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2));
            when(distributor.distribute(List.of(s1, s2)))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(resp));
            when(batchRestExecutor.execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660")))
                    .thenReturn(BatchResult.skip("000660", "테스트 skip"));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("키 1개, 3종목 중 1개만 mid-run KisTokenIssueException — 나머지 2개 계속 진행 (AC-4)")
        void collect_midRunTokenException_onlyAffectedStockSkipped() {
            // Arrange: one key, 3 stocks — middle stock throws KisTokenIssueException mid-run
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660"); // dies mid-run
            Stock s3 = stockOf("035720");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2, s3));
            when(distributor.distribute(List.of(s1, s2, s3)))
                    .thenReturn(Map.of(ISA, List.of(s1, s2, s3)));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(resp));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660")))
                    .thenThrow(
                            new KisTokenIssueException(
                                    "isa", new RuntimeException("mid-run death")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("035720")))
                    .thenReturn(BatchResult.success(resp));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert: only the dead stock is skipped, batch does not fail
            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(2);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("collect — 모든 키 죽음 (AC-3, REQ-KEYDIST-020)")
    class AllKeysDead {

        private Logger serviceLogger;
        private ListAppender<ILoggingEvent> listAppender;

        @BeforeEach
        void attachLogAppender() {
            serviceLogger =
                    (Logger) LoggerFactory.getLogger(DomesticDailyOhlcvCollectionService.class);
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
        @DisplayName(
                "distributor 빈 할당 반환 — batchRestExecutor.execute 0회, 전체 skip, 시도=total, ERROR 로그 1회 (AC-3)")
        void collect_emptyDistributorAllocation_zeroBatchCalls() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            Stock s3 = stockOf("035720");
            List<Stock> stocks = List.of(s1, s2, s3);
            when(stockRepository.findAllActive()).thenReturn(stocks);
            when(distributor.distribute(stocks)).thenReturn(Map.of());

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert: per-stock collection 0 calls (AC-3 HARD rule)
            verify(batchRestExecutor, never())
                    .execute(any(), any(), anyString(), any(), anyString());
            // All stocks skipped, none succeeded
            assertThat(result.attempted()).isEqualTo(3);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(3);
            // Exactly ONE ERROR-level log emitted on all-keys-dead path (AC-3 REQ-KEYDIST-020)
            List<ILoggingEvent> errorLogs =
                    listAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errorLogs).hasSize(1);
        }
    }

    @Nested
    @DisplayName("collect — 검증 필터링 (REQ-BATCH-033)")
    class CollectValidation {

        @Test
        @DisplayName("종가 0 이하인 행 — insertIgnoreDuplicate 미호출")
        void collect_zeroPriceRow_notInserted() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            KisDailyOhlcvResponse.DailyOhlcvRow invalidRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605",
                            "0",
                            "74000",
                            "76000",
                            "73000",
                            "1000000",
                            "75000000000",
                            "N");
            KisDailyOhlcvResponse response = stubResponse(List.of(invalidRow));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert — insertIgnoreDuplicate must not be called for invalid row
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Long.class),
                            any(Long.class));
        }

        @Test
        @DisplayName("거래량 0 이하인 행 — insertIgnoreDuplicate 미호출")
        void collect_zeroVolumeRow_notInserted() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            KisDailyOhlcvResponse.DailyOhlcvRow invalidRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605", "75000", "74000", "76000", "73000", "0", "0", "N");
            KisDailyOhlcvResponse response = stubResponse(List.of(invalidRow));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            verify(dailyOhlcvRepository, never())
                    .insertIgnoreDuplicate(
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(),
                            any(Long.class),
                            any(Long.class));
        }

        @Test
        @DisplayName("유효한 행 — insertIgnoreDuplicate 1회 호출")
        void collect_validRow_insertsOnce() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(distributor.distribute(List.of(stock))).thenReturn(Map.of(ISA, List.of(stock)));

            KisDailyOhlcvResponse response = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(response));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert
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
        }
    }

    @Nested
    @DisplayName("collect — 건강 키 기반 분산 + distributor 위임 (AC-7, REQ-KEYDIST-010)")
    class HealthyKeyDistribution {

        @Test
        @DisplayName("5키 중 3키 건강 — distributor 위임 검증, 죽은 키 자격증명이 batchRestExecutor에 미도달 (AC-7)")
        void collect_threeHealthyOutOfFive_delegatesToDistributor() {
            // Arrange: distributor returns 3-key allocation (dead keys KEY4, KEY5 excluded)
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            Stock s3 = stockOf("035720");
            Stock s4 = stockOf("005380");
            Stock s5 = stockOf("068270");
            List<Stock> stocks = List.of(s1, s2, s3, s4, s5);

            when(stockRepository.findAllActive()).thenReturn(stocks);
            // Distributor distributes 5 stocks across 3 healthy keys, excluding STOCK_KEY and DC
            when(distributor.distribute(stocks))
                    .thenReturn(
                            Map.of(
                                    ISA, List.of(s1, s4),
                                    GOLD, List.of(s2, s5),
                                    PENSION, List.of(s3)));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            anyString()))
                    .thenReturn(BatchResult.success(resp));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert: distributor was called with the active stock list (delegation verified)
            verify(distributor).distribute(stocks);
            // Dead keys never reach batchRestExecutor
            verify(batchRestExecutor, never())
                    .execute(eq(STOCK_KEY), any(), anyString(), any(), anyString());
            verify(batchRestExecutor, never())
                    .execute(eq(DC), any(), anyString(), any(), anyString());
            // All 5 stocks processed through healthy keys
            assertThat(result.attempted()).isEqualTo(5);
            assertThat(result.succeeded()).isEqualTo(5);
        }

        @Test
        @DisplayName("건강 키 기반 라운드로빈 — 각 건강 키에 올바른 종목 할당됨 (AC-7 regression pin: blind RR 제거)")
        void collect_healthyKeyBasis_correctStockToKeyAssignment() {
            // Arrange: 2 healthy keys, 2 stocks — each key gets exactly 1 stock
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            List<Stock> stocks = List.of(s1, s2);

            when(stockRepository.findAllActive()).thenReturn(stocks);
            when(distributor.distribute(stocks))
                    .thenReturn(Map.of(ISA, List.of(s1), GOLD, List.of(s2)));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(resp));
            when(batchRestExecutor.execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660")))
                    .thenReturn(BatchResult.success(resp));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert: each healthy credential used exactly once with its assigned stock
            verify(batchRestExecutor, times(1))
                    .execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930"));
            verify(batchRestExecutor, times(1))
                    .execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660"));
        }
    }
}
