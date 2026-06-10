package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomesticDailyOhlcvCollectionService 단위 테스트")
class DomesticDailyOhlcvCollectionServiceTest {

    private static final KisAccountCredential ISA =
            new KisAccountCredential("isa", "11111111", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD =
            new KisAccountCredential("gold", "22222222", "appkey-gold", "appsecret-gold");

    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private KisProperties kisProperties;
    @Mock private BatchRestExecutor batchRestExecutor;

    private DomesticDailyOhlcvCollectionService service;

    @BeforeEach
    void setUp() {
        when(kisProperties.accounts()).thenReturn(List.of(ISA, GOLD));
        service =
                new DomesticDailyOhlcvCollectionService(
                        stockRepository, dailyOhlcvRepository, kisProperties, batchRestExecutor);
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

    @Nested
    @DisplayName("collect — 성공 경로")
    class CollectSuccess {

        @Test
        @DisplayName("활성 종목 1개 — 수집 성공, 시도=1, 성공=1, skip=0 (AC-4 S4-1)")
        void collect_oneActiveStock_successResult() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));

            KisDailyOhlcvResponse.DailyOhlcvRow row = validRow("20260605");
            KisDailyOhlcvResponse response = stubResponse(List.of(row));
            BatchResult<KisDailyOhlcvResponse> batchResult = BatchResult.success(response);
            when(batchRestExecutor.execute(
                            any(),
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
        @DisplayName("활성 종목 2개 — 결과 집계 시도=2, 성공=2, skip=0")
        void collect_twoActiveStocks_aggregatesCorrectly() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2));

            KisDailyOhlcvResponse r1 = stubResponse(List.of(validRow("20260605")));
            KisDailyOhlcvResponse r2 = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(r1));
            when(batchRestExecutor.execute(
                            any(),
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
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.skip("005930"));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(1);
            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("KisTokenIssueException — graceful skip, 배치 미실패 (REQ-BATCH-025)")
        void collect_tokenIssueException_gracefulSkip() {
            // Arrange
            Stock stock = stockOf("005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            when(batchRestExecutor.execute(
                            any(),
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
        @DisplayName("종목 2개 중 1개 skip — 시도=2, 성공=1, skip=1 (AC-5 S5-3)")
        void collect_oneSkipOneSuccess_correctMeta() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2));

            KisDailyOhlcvResponse resp = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("005930")))
                    .thenReturn(BatchResult.success(resp));
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            eq("000660")))
                    .thenReturn(BatchResult.skip("000660"));

            // Act
            CollectionResult result = service.collect(LocalDate.of(2026, 6, 5));

            // Assert
            assertThat(result.attempted()).isEqualTo(2);
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
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
                            any(),
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

            KisDailyOhlcvResponse.DailyOhlcvRow invalidRow =
                    new KisDailyOhlcvResponse.DailyOhlcvRow(
                            "20260605", "75000", "74000", "76000", "73000", "0", "0", "N");
            KisDailyOhlcvResponse response = stubResponse(List.of(invalidRow));
            when(batchRestExecutor.execute(
                            any(),
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

            KisDailyOhlcvResponse response = stubResponse(List.of(validRow("20260605")));
            when(batchRestExecutor.execute(
                            any(),
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
    @DisplayName("collect — 라운드로빈 키 분산 (REQ-BATCH-001)")
    class RoundRobinDistribution {

        @Test
        @DisplayName("종목 2개, 키 2개 — 각 키가 1번씩 사용됨")
        void collect_twoStocksTwoKeys_distributedAcrossKeys() {
            // Arrange
            Stock s1 = stockOf("005930");
            Stock s2 = stockOf("000660");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2));

            KisDailyOhlcvResponse resp = stubResponse(List.of());
            when(batchRestExecutor.execute(
                            any(),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            anyString()))
                    .thenReturn(BatchResult.success(resp));

            // Act
            service.collect(LocalDate.of(2026, 6, 5));

            // Assert — each credential used exactly once
            verify(batchRestExecutor, times(1))
                    .execute(
                            eq(ISA),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            anyString());
            verify(batchRestExecutor, times(1))
                    .execute(
                            eq(GOLD),
                            any(),
                            anyString(),
                            eq(KisDailyOhlcvResponse.class),
                            anyString());
        }
    }

    // Helper: build KisDailyOhlcvResponse with given rows
    private KisDailyOhlcvResponse stubResponse(List<KisDailyOhlcvResponse.DailyOhlcvRow> rows) {
        return new KisDailyOhlcvResponse("0", "MCA00000", "조회되었습니다.", rows);
    }
}
