package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.stock.enums.Market;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MarketConverterTest {

    private final MarketConverter converter = new MarketConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 국내_시장_상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(Market.KOSPI)).isEqualTo("KOSPI");
            assertThat(converter.convertToDatabaseColumn(Market.KOSDAQ)).isEqualTo("KOSDAQ");
            assertThat(converter.convertToDatabaseColumn(Market.KRX)).isEqualTo("KRX");
        }

        @Test
        void 해외_시장_상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(Market.NYSE)).isEqualTo("NYSE");
            assertThat(converter.convertToDatabaseColumn(Market.NASDAQ)).isEqualTo("NASDAQ");
            assertThat(converter.convertToDatabaseColumn(Market.AMEX)).isEqualTo("AMEX");
            assertThat(converter.convertToDatabaseColumn(Market.US)).isEqualTo("US");
        }

        @Test
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }
    }

    @Nested
    @DisplayName("convertToEntityAttribute")
    class ConvertToEntityAttribute {

        @Test
        void 국내_시장_문자열을_상수로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToEntityAttribute("KOSPI")).isEqualTo(Market.KOSPI);
            assertThat(converter.convertToEntityAttribute("KOSDAQ")).isEqualTo(Market.KOSDAQ);
            assertThat(converter.convertToEntityAttribute("KRX")).isEqualTo(Market.KRX);
        }

        @Test
        void 해외_시장_문자열을_상수로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToEntityAttribute("NYSE")).isEqualTo(Market.NYSE);
            assertThat(converter.convertToEntityAttribute("NASDAQ")).isEqualTo(Market.NASDAQ);
            assertThat(converter.convertToEntityAttribute("AMEX")).isEqualTo(Market.AMEX);
            assertThat(converter.convertToEntityAttribute("US")).isEqualTo(Market.US);
        }

        @Test
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        void 미지값이면_IllegalArgumentException을_던진다() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
