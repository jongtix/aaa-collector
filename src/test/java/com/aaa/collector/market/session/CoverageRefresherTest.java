package com.aaa.collector.market.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoverageRefresher — 일봉 커버리지 일1회 계산 (REQ-WM-010/011)")
class CoverageRefresherTest {

    @Mock private MarketSessionGate marketSessionGate;
    @Mock private UsMarketSessionGate usMarketSessionGate;
    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private CoverageMetrics coverageMetrics;

    @InjectMocks private CoverageRefresher refresher;

    private static Stock stock(long id, Market market) {
        return Stock.builder()
                .symbol("SYM" + id)
                .nameKo("종목" + id)
                .market(market)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("KRX 커버리지")
    class KrxCoverage {

        @Test
        @DisplayName("expected watermark 없으면(null) 계산을 스킵한다")
        void skipsWhenExpectedIsNull() {
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(null);

            refresher.refreshKrxCoverage();

            verify(coverageMetrics, never()).setRatio(any(), any(Double.class));
        }

        @Test
        @DisplayName("유니버스 대비 커버리지 비율을 계산해 게이지에 반영한다")
        void computesRatioAndSetsGauge() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            List<Stock> universe =
                    List.of(
                            stock(1, Market.KOSPI),
                            stock(2, Market.KOSPI),
                            stock(3, Market.KOSDAQ));
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(universe);
            when(dailyOhlcvRepository.countDistinctStockIdsByTradeDateAndStockIdIn(
                            eq(expected), anyCollection()))
                    .thenReturn(2L);

            refresher.refreshKrxCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 2.0 / 3.0);
        }

        @Test
        @DisplayName("유니버스가 비면 0.0으로 설정한다 (0 나눗셈 방지)")
        void emptyUniverse_setsZero() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of());

            refresher.refreshKrxCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.0);
        }
    }

    @Nested
    @DisplayName("US 커버리지")
    class UsCoverage {

        @Test
        @DisplayName("expected watermark 없으면(null) 계산을 스킵한다")
        void skipsWhenExpectedIsNull() {
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);

            refresher.refreshUsCoverage();

            verify(coverageMetrics, never()).setRatio(any(), any(Double.class));
        }

        @Test
        @DisplayName("유니버스 대비 커버리지 비율을 계산해 게이지에 반영한다")
        void computesRatioAndSetsGauge() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            List<Stock> universe = List.of(stock(1, Market.NASDAQ), stock(2, Market.NYSE));
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(universe);
            when(dailyOhlcvRepository.countDistinctStockIdsByTradeDateAndStockIdIn(
                            eq(expected), anyCollection()))
                    .thenReturn(2L);

            refresher.refreshUsCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_US, 1.0);
        }
    }
}
