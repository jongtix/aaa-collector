package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
import java.time.LocalDateTime;
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
@DisplayName("ShortSaleOverseasDailyCollectionService — Daily 합산+매칭+검증")
class ShortSaleOverseasDailyCollectionServiceTest {

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
        // T5 LOCF forward 배치 조회 — Daily 테스트는 forward 매칭 없음(빈 Map) 기본값으로 둔다(lenient)
        Mockito.lenient()
                .when(
                        shortSaleOverseasRepository.findLatestShortInterestByStockIds(
                                anyCollection(), any()))
                .thenReturn(Map.of());
    }

    private static Stock stock(long id, String symbol, Market market, AssetType assetType) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("종목_" + symbol)
                        .market(market)
                        .assetType(assetType)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static FinraRegShoDailyResponse dailyRow(String symbol, long shortQty, long totalQty) {
        return new FinraRegShoDailyResponse(
                TRADE_DATE, symbol, BigDecimal.valueOf(shortQty), BigDecimal.valueOf(totalQty));
    }

    @Nested
    @DisplayName("reportingFacility 합산 (AC-DAILY-1)")
    class Aggregation {

        @Test
        @DisplayName("동일 종목·거래일의 다중 시설 행을 합산해 1행으로 UPSERT한다")
        void sumsFacilityRows() {
            // Arrange: AAPL 시설 3행(페이지 경계로 분산된 것처럼 전 페이지 누적된 리스트)
            Stock aapl = stock(1L, "AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(
                            List.of(
                                    dailyRow("AAPL", 69_397, 154_130),
                                    dailyRow("AAPL", 4_891_733, 17_699_980),
                                    dailyRow("AAPL", 198_716, 588_753)));

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert: short_volume=5159846, total_volume=18442863 합산
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(TRADE_DATE),
                            eq(5_159_846L),
                            eq(18_442_863L),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(result.succeeded()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("미국 STOCK+ETF 매칭, 국내·미매칭 제외 (AC-MATCH-1)")
    class Matching {

        @Test
        @DisplayName("매칭 종목만 적재하고 미매칭 심볼은 적재하지 않는다")
        void matchesUsStocksOnly() {
            // Arrange: 활성 미국 종목 = AAPL만. FINRA 응답엔 AAPL + 미매칭 ZZZZ
            Stock aapl = stock(1L, "AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(List.of(dailyRow("AAPL", 100, 200), dailyRow("ZZZZ", 300, 400)));

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L), eq(TRADE_DATE), eq(100L), eq(200L), any(), isNull(), isNull());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(eq(1L), eq(TRADE_DATE), eq(300L), eq(400L), any(), any(), any());
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("슬래시 클래스주식(BRK/B)을 정규화(BRK.B)해 stocks.symbol과 매칭한다 (AC-NORM-1)")
        void matchesNormalizedClassShare() {
            // Arrange: stocks엔 BRK.B 표기, FINRA는 BRK/B 표기
            Stock brk = stock(2L, "BRK.B", Market.NYSE, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(brk));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(List.of(dailyRow("BRK/B", 500, 900)));

            // Act
            service.collectDaily(TRADE_DATE);

            // Assert
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(2L), eq(TRADE_DATE), eq(500L), eq(900L), any(), isNull(), isNull());
        }
    }

    @Nested
    @DisplayName("검증·skip (AC-VALIDATE-1, AC-EMPTY-1)")
    class Validation {

        @Test
        @DisplayName("음수/소수부 있는 수량 행은 skip+WARN, 적재하지 않는다 (REQ-SSO-021)")
        void skipsInvalidRows() {
            // Arrange: AAPL 음수, MSFT 소수부(무손실 변환 불가), GOOG 정상
            Stock aapl = stock(1L, "AAPL", Market.NASDAQ, AssetType.STOCK);
            Stock msft = stock(2L, "MSFT", Market.NASDAQ, AssetType.STOCK);
            Stock goog = stock(3L, "GOOG", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable())
                    .thenReturn(List.of(aapl, msft, goog));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(
                            List.of(
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "AAPL",
                                            BigDecimal.valueOf(-1),
                                            BigDecimal.valueOf(100)),
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "MSFT",
                                            new BigDecimal("10.5"),
                                            BigDecimal.valueOf(100)),
                                    dailyRow("GOOG", 300, 400)));

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert: GOOG만 적재
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(3L), eq(TRADE_DATE), eq(300L), eq(400L), any(), isNull(), isNull());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(eq(1L), any(), anyLong(), anyLong(), any(), any(), any());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(eq(2L), any(), anyLong(), anyLong(), any(), any(), any());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(2);
        }

        @Test
        @DisplayName("빈 응답이면 적재 0건·정상 skip, 예외 없음 (AC-EMPTY-1, REQ-SSO-020)")
        void emptyResponseSkips() {
            // Arrange: 빈 응답이면 종목 조회 전에 단락되므로 stocks 스텁 불필요
            when(finraClient.fetchRegShoDaily(TRADE_DATE)).thenReturn(List.of());

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(anyLong(), any(), anyLong(), anyLong(), any(), any(), any());
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
        }
    }
}
