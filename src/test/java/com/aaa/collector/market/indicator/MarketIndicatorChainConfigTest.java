package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.market.indicator.vix.CboeVixClient;
import com.aaa.collector.market.session.MarketSessionGate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MarketIndicatorChainConfig} 빈 배선 단위 테스트 (SPEC-COLLECTOR-MARKETIND-006 AC-05).
 *
 * <p>스프링 컨텍스트 없이 config 메서드를 직접 호출해 배선 결과(체인 동작)를 검증한다. 풀컨텍스트 로드 회귀는 기존 {@code
 * AaaCollectorApplicationTests}(smoke)가 커버한다.
 */
@DisplayName("MarketIndicatorChainConfig 단위 테스트")
class MarketIndicatorChainConfigTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);

    private final MarketIndicatorChainConfig config = new MarketIndicatorChainConfig();

    private MarketIndicatorRow row(IndicatorCode indicatorCode, String source) {
        return new MarketIndicatorRow(
                indicatorCode,
                DATE,
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                source);
    }

    @Nested
    @DisplayName("usdkrwChain — MarketSessionGate 주입 배선 (REQ-013, AC-05)")
    class UsdkrwChainWiring {

        @Test
        @DisplayName("휴장일(isOpenDay=false) — KOREAEXIM 빈 결과가 예상 무데이터로 기록되고 Yahoo 폴백 진행")
        void holiday_recordsExpectedNoDataAndFallsBackToYahoo() {
            // Arrange
            KoreaeximExchangeRateClient koreaeximClient = mock(KoreaeximExchangeRateClient.class);
            when(koreaeximClient.fetchDaily(DATE)).thenReturn(List.of());
            when(koreaeximClient.sourceName()).thenReturn("KOREAEXIM");

            YahooFinanceClient yahooFinanceClient = mock(YahooFinanceClient.class);
            MarketIndicatorRow yahooRow = row(IndicatorCode.USDKRW, "YAHOO_USDKRW");
            when(yahooFinanceClient.fetchDaily(IndicatorCode.USDKRW, DATE))
                    .thenReturn(List.of(yahooRow));
            MarketIndicatorSource yahooUsdkrwSource = config.yahooUsdkrwSource(yahooFinanceClient);

            MarketIndicatorMetrics metrics = mock(MarketIndicatorMetrics.class);
            MarketSessionGate marketSessionGate = mock(MarketSessionGate.class);
            when(marketSessionGate.isOpenDay(DATE)).thenReturn(false);

            MarketIndicatorSourceChain chain =
                    config.usdkrwChain(
                            koreaeximClient, yahooUsdkrwSource, metrics, marketSessionGate);

            // Act
            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            // Assert
            assertThat(result).containsExactly(yahooRow);
            verify(metrics).recordExpectedNoData("USDKRW", "KOREAEXIM");
        }

        @Test
        @DisplayName("개장일(isOpenDay=true) — KOREAEXIM 빈 결과는 예상 무데이터로 기록되지 않는다")
        void openDay_doesNotRecordExpectedNoData() {
            // Arrange
            KoreaeximExchangeRateClient koreaeximClient = mock(KoreaeximExchangeRateClient.class);
            when(koreaeximClient.fetchDaily(DATE)).thenReturn(List.of());
            when(koreaeximClient.sourceName()).thenReturn("KOREAEXIM");

            YahooFinanceClient yahooFinanceClient = mock(YahooFinanceClient.class);
            MarketIndicatorRow yahooRow = row(IndicatorCode.USDKRW, "YAHOO_USDKRW");
            when(yahooFinanceClient.fetchDaily(IndicatorCode.USDKRW, DATE))
                    .thenReturn(List.of(yahooRow));
            MarketIndicatorSource yahooUsdkrwSource = config.yahooUsdkrwSource(yahooFinanceClient);

            MarketIndicatorMetrics metrics = mock(MarketIndicatorMetrics.class);
            MarketSessionGate marketSessionGate = mock(MarketSessionGate.class);
            when(marketSessionGate.isOpenDay(DATE)).thenReturn(true);

            MarketIndicatorSourceChain chain =
                    config.usdkrwChain(
                            koreaeximClient, yahooUsdkrwSource, metrics, marketSessionGate);

            // Act
            chain.fetchDaily(DATE);

            // Assert
            verify(metrics, never()).recordExpectedNoData(any(), any());
        }
    }

    @Nested
    @DisplayName("vixChain — 예상-빈 조건 미배선, diff 0 무회귀 (REQ-014)")
    class VixChainNoRegression {

        @Test
        @DisplayName("CBOE 빈 결과 — 예상 무데이터로 기록되지 않고 Yahoo^VIX로 폴백")
        void cboeEmpty_neverRecordsExpectedNoData() {
            // Arrange
            CboeVixClient cboeVixClient = mock(CboeVixClient.class);
            when(cboeVixClient.fetchDaily(DATE)).thenReturn(List.of());
            when(cboeVixClient.sourceName()).thenReturn("CBOE");

            YahooFinanceClient yahooFinanceClient = mock(YahooFinanceClient.class);
            MarketIndicatorRow yahooRow = row(IndicatorCode.VIX, "YAHOO_VIX");
            when(yahooFinanceClient.fetchDaily(IndicatorCode.VIX, DATE))
                    .thenReturn(List.of(yahooRow));
            MarketIndicatorSource yahooVixSource = config.yahooVixSource(yahooFinanceClient);

            MarketIndicatorMetrics metrics = mock(MarketIndicatorMetrics.class);

            MarketIndicatorSourceChain chain =
                    config.vixChain(cboeVixClient, yahooVixSource, metrics);

            // Act
            List<MarketIndicatorRow> result = chain.fetchDaily(DATE);

            // Assert
            assertThat(result).containsExactly(yahooRow);
            verify(metrics, never()).recordExpectedNoData(any(), any());
        }
    }
}
