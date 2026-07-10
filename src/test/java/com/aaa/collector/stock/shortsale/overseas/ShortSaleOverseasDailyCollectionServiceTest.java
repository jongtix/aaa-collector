package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
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
    @Mock private UsMarketOpenGate usMarketOpenGate;
    @Mock private WatermarkMetrics watermarkMetrics;

    private ShortSaleOverseasDailyCollectionService service;

    @BeforeEach
    void setUp() {
        // 기존 테스트는 휴장일 게이트 행동을 검증하지 않으므로 always-open으로 스텁한다
        Mockito.lenient().when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
        service =
                new ShortSaleOverseasDailyCollectionService(
                        finraClient,
                        stockRepository,
                        shortSaleOverseasRepository,
                        batchMetrics,
                        usMarketOpenGate,
                        watermarkMetrics);
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

    /** scale에 무관하게 값으로 비교하는 BigDecimal 인자 매처(합산 결과 scale 편차 흡수). */
    private static BigDecimal bd(String value) {
        BigDecimal expected = new BigDecimal(value);
        return argThat(actual -> actual != null && actual.compareTo(expected) == 0);
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
                            bd("5159846"),
                            bd("18442863"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(result.succeeded()).isEqualTo(1);
        }

        @Test
        @DisplayName("소수부를 가진 시설 행도 skip 없이 무손실 합산한다 (2026-02-23 FINRA 소수 전환, REQ-SSD-006/012/013)")
        void preservesFractionalFacilityRows() {
            // Arrange: AAPL 시설 2행 — 둘 다 소수 6자리(FINRA 2026-02-23 이후 실측 형태)
            Stock aapl = stock(1L, "AAPL", Market.NASDAQ, AssetType.STOCK);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(aapl));
            when(finraClient.fetchRegShoDaily(TRADE_DATE))
                    .thenReturn(
                            List.of(
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "AAPL",
                                            new BigDecimal("11479561.984835"),
                                            new BigDecimal("240101.320702")),
                                    new FinraRegShoDailyResponse(
                                            TRADE_DATE,
                                            "AAPL",
                                            new BigDecimal("0.015165"),
                                            new BigDecimal("0.679298"))));

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert: 소수 합산이 무손실 — short=11479562.000000, total=240102.000000, 종목 전체 skip 아님
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(1L),
                            eq(TRADE_DATE),
                            bd("11479562.000000"),
                            bd("240102.000000"),
                            any(LocalDateTime.class),
                            isNull(),
                            isNull());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isZero();
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
                            eq(1L),
                            eq(TRADE_DATE),
                            bd("100"),
                            bd("200"),
                            any(),
                            isNull(),
                            isNull());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(eq(1L), eq(TRADE_DATE), bd("300"), bd("400"), any(), any(), any());
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
                            eq(2L),
                            eq(TRADE_DATE),
                            bd("500"),
                            bd("900"),
                            any(),
                            isNull(),
                            isNull());
        }
    }

    @Nested
    @DisplayName("검증·skip (AC-VALIDATE-1, AC-EMPTY-1)")
    class Validation {

        @Test
        @DisplayName("음수/scale 초과 수량 행은 skip+WARN, 적재하지 않는다 (REQ-SSO-021, REQ-SSD-010/011)")
        void skipsInvalidRows() {
            // Arrange: AAPL 음수, MSFT scale 초과(7자리 소수 — fail-loud 거부), GOOG 정상
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
                                            new BigDecimal("10.1234567"),
                                            BigDecimal.valueOf(100)),
                                    dailyRow("GOOG", 300, 400)));

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert: GOOG만 적재
            verify(shortSaleOverseasRepository)
                    .upsertDaily(
                            eq(3L),
                            eq(TRADE_DATE),
                            bd("300"),
                            bd("400"),
                            any(),
                            isNull(),
                            isNull());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            eq(1L),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            verify(shortSaleOverseasRepository, never())
                    .upsertDaily(
                            eq(2L),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(2);
        }

        @Test
        @DisplayName("미장 휴장일 skip 시 last_load를 stamp한다(정상 no-op, REQ-XR-011)")
        void holidaySkipStampsCompletion() {
            // Arrange: 미장 휴장일 → collectDaily가 skip 경로로 단락
            when(usMarketOpenGate.isOpenDay(TRADE_DATE)).thenReturn(false);

            // Act
            ShortSaleOverseasDailyCollectionService.DailyResult result =
                    service.collectDaily(TRADE_DATE);

            // Assert: FINRA 호출 없이 0-집계로 stamp(실패가 아니라 예상된 no-op)
            verify(batchMetrics).recordCompletion("overseas-shortsale-daily", 0L, 0L, 0L, 0L);
            verify(finraClient, never()).fetchRegShoDaily(any());
            assertThat(result.attempted()).isZero();
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
                    .upsertDaily(
                            anyLong(),
                            any(),
                            any(BigDecimal.class),
                            any(BigDecimal.class),
                            any(),
                            any(),
                            any());
            assertThat(result.attempted()).isZero();
            assertThat(result.succeeded()).isZero();
        }
    }
}
