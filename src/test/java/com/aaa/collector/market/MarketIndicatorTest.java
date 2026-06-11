package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MarketIndicator builder 검증")
class MarketIndicatorTest {

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("indicatorCode와 tradeDate가 설정된다")
        void marketIndicator_indicatorCodeAndTradeDateSet() {
            LocalDate tradeDate = LocalDate.of(2026, 6, 11);

            MarketIndicator indicator =
                    MarketIndicator.builder()
                            .indicatorCode(IndicatorCode.VIX)
                            .tradeDate(tradeDate)
                            .build();

            assertThat(indicator.getIndicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(indicator.getTradeDate()).isEqualTo(tradeDate);
        }

        @Test
        @DisplayName("OHLC 가격 필드들이 설정된다")
        void marketIndicator_ohlcFieldsSet() {
            MarketIndicator indicator =
                    MarketIndicator.builder()
                            .indicatorCode(IndicatorCode.VIX)
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .openValue(new BigDecimal("18.5000"))
                            .highValue(new BigDecimal("19.2000"))
                            .lowValue(new BigDecimal("17.8000"))
                            .closeValue(new BigDecimal("18.9000"))
                            .build();

            assertThat(indicator.getOpenValue()).isEqualByComparingTo("18.5000");
            assertThat(indicator.getHighValue()).isEqualByComparingTo("19.2000");
            assertThat(indicator.getLowValue()).isEqualByComparingTo("17.8000");
            assertThat(indicator.getCloseValue()).isEqualByComparingTo("18.9000");
        }

        @Test
        @DisplayName("source 필드가 설정된다")
        void marketIndicator_sourceSet() {
            MarketIndicator indicator =
                    MarketIndicator.builder()
                            .indicatorCode(IndicatorCode.VIX)
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .source("CBOE")
                            .build();

            assertThat(indicator.getSource()).isEqualTo("CBOE");
        }

        @Test
        @DisplayName("USDKRW indicatorCode가 설정된다")
        void marketIndicator_usdkrwIndicatorCode() {
            MarketIndicator indicator =
                    MarketIndicator.builder()
                            .indicatorCode(IndicatorCode.USDKRW)
                            .tradeDate(LocalDate.of(2026, 6, 1))
                            .closeValue(new BigDecimal("1380.0000"))
                            .build();

            assertThat(indicator.getIndicatorCode()).isEqualTo(IndicatorCode.USDKRW);
        }

        @Test
        @DisplayName("nullable OHLC 필드에 null을 설정할 수 있다")
        void marketIndicator_nullableOhlcAcceptNull() {
            MarketIndicator indicator =
                    MarketIndicator.builder()
                            .indicatorCode(IndicatorCode.VIX)
                            .tradeDate(LocalDate.of(2026, 1, 1))
                            .openValue(null)
                            .closeValue(new BigDecimal("15.0000"))
                            .build();

            assertThat(indicator.getOpenValue()).isNull();
            assertThat(indicator.getCloseValue()).isEqualByComparingTo("15.0000");
        }
    }
}
