package com.aaa.collector.stock.shortsale.overseas;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleOverseasDailyCollectionService — BatchMetrics Daily 집계 (REQ-SSO-040)")
class ShortSaleOverseasDailyMetricsTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 1, 6);

    @Mock private FinraShortSaleClient finraClient;
    @Mock private StockRepository stockRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private BatchMetrics batchMetrics;

    private ShortSaleOverseasDailyCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new ShortSaleOverseasDailyCollectionService(
                        finraClient, stockRepository, shortSaleOverseasRepository, batchMetrics);
        Mockito.lenient()
                .when(
                        shortSaleOverseasRepository.findLatestShortInterestByStockIds(
                                anyCollection(), any()))
                .thenReturn(Map.of());
    }

    private static Stock stock(long id, String symbol) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Nested
    @DisplayName("Daily 집계 — batch 라벨 overseas-shortsale-daily")
    class DailyMetrics {

        @Test
        @DisplayName("성공/skip을 recordCompletion으로 기록한다(fail = target-success-skip)")
        void recordsDailyCompletion() {
            // Arrange: AAPL 정상, MSFT 음수(skip)
            Stock aapl = stock(1L, "AAPL");
            Stock msft = stock(2L, "MSFT");
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl, msft));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(
                            List.of(
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "AAPL",
                                            BigDecimal.valueOf(100),
                                            BigDecimal.valueOf(200)),
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "MSFT",
                                            BigDecimal.valueOf(-1),
                                            BigDecimal.valueOf(100))));

            // Act
            service.collectDaily(TRADE_DATE);

            // Assert: attempted=2, success=1, skip=1, fail=0
            verify(batchMetrics).recordCompletion("overseas-shortsale-daily", 2L, 1L, 0L, 1L);
        }

        @Test
        @DisplayName("빈 응답이면 0건으로 기록한다(내용 검증 후 — REQ-SSO-030)")
        void recordsZeroOnEmpty() {
            when(finraClient.fetchRegShoDaily(TRADE_DATE)).thenReturn(List.of());

            // Act
            service.collectDaily(TRADE_DATE);

            // Assert
            verify(batchMetrics).recordCompletion("overseas-shortsale-daily", 0L, 0L, 0L, 0L);
        }
    }
}
