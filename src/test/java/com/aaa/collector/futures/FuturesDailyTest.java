package com.aaa.collector.futures;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FuturesDaily builder 검증")
class FuturesDailyTest {

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("seriesCode, contractCode, exchangeCode가 설정된다")
        void futuresDaily_identifierFieldsSet() {
            FuturesDaily fd =
                    FuturesDaily.builder()
                            .seriesCode("ES")
                            .contractCode("ESM26")
                            .exchangeCode("CME")
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .build();

            assertThat(fd.getSeriesCode()).isEqualTo("ES");
            assertThat(fd.getContractCode()).isEqualTo("ESM26");
            assertThat(fd.getExchangeCode()).isEqualTo("CME");
        }

        @Test
        @DisplayName("OHLC 가격 필드들이 설정된다")
        void futuresDaily_ohlcFieldsSet() {
            FuturesDaily fd =
                    FuturesDaily.builder()
                            .seriesCode("ES")
                            .contractCode("ESM26")
                            .exchangeCode("CME")
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .openPrice(new BigDecimal("5300.00"))
                            .highPrice(new BigDecimal("5350.00"))
                            .lowPrice(new BigDecimal("5280.00"))
                            .closePrice(new BigDecimal("5340.00"))
                            .build();

            assertThat(fd.getOpenPrice()).isEqualByComparingTo("5300.00");
            assertThat(fd.getHighPrice()).isEqualByComparingTo("5350.00");
            assertThat(fd.getLowPrice()).isEqualByComparingTo("5280.00");
            assertThat(fd.getClosePrice()).isEqualByComparingTo("5340.00");
        }

        @Test
        @DisplayName("volume과 openInterest가 설정된다")
        void futuresDaily_volumeAndOpenInterestSet() {
            FuturesDaily fd =
                    FuturesDaily.builder()
                            .seriesCode("ES")
                            .contractCode("ESM26")
                            .exchangeCode("CME")
                            .tradeDate(LocalDate.of(2026, 6, 11))
                            .volume(1_200_000L)
                            .openInterest(3_500_000L)
                            .build();

            assertThat(fd.getVolume()).isEqualTo(1_200_000L);
            assertThat(fd.getOpenInterest()).isEqualTo(3_500_000L);
        }

        @Test
        @DisplayName("CONTINUOUS_CONTRACT_CODE 상수가 CONTINUOUS 값이다")
        void futuresDaily_continuousContractCodeConstant() {
            assertThat(FuturesDaily.CONTINUOUS_CONTRACT_CODE).isEqualTo("CONTINUOUS");
        }

        @Test
        @DisplayName("연속선물 contractCode로 생성하면 CONTINUOUS_CONTRACT_CODE와 동일하다")
        void futuresDaily_continuousContractCode() {
            FuturesDaily fd =
                    FuturesDaily.builder()
                            .seriesCode("ES")
                            .contractCode(FuturesDaily.CONTINUOUS_CONTRACT_CODE)
                            .exchangeCode("CME")
                            .tradeDate(LocalDate.of(2026, 1, 1))
                            .build();

            assertThat(fd.getContractCode()).isEqualTo("CONTINUOUS");
        }
    }
}
