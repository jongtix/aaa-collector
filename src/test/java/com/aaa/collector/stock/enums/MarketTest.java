package com.aaa.collector.stock.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Market enum 단위 테스트")
class MarketTest {

    @Nested
    @DisplayName("toDailyPriceExcd — KIS dailyprice EXCD 매핑 (REQ-OVOH-002, AC-PATH-2)")
    class ToDailyPriceExcd {

        @Test
        @DisplayName("NYSE→NYS, NASDAQ→NAS, AMEX→AMS (SPY=AMS 실측 정합)")
        void mapsUsMarketsToExcd() {
            assertThat(Market.NYSE.toDailyPriceExcd()).isEqualTo("NYS");
            assertThat(Market.NASDAQ.toDailyPriceExcd()).isEqualTo("NAS");
            assertThat(Market.AMEX.toDailyPriceExcd()).isEqualTo("AMS");
        }

        @Test
        @DisplayName("국내·지수 시장 — dailyprice EXCD 미정의로 예외 (호출자 skip 처리)")
        void throwsForNonUsMarkets() {
            assertThatThrownBy(Market.KOSPI::toDailyPriceExcd)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(Market.KOSDAQ::toDailyPriceExcd)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(Market.KRX::toDailyPriceExcd)
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(Market.US::toDailyPriceExcd)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
