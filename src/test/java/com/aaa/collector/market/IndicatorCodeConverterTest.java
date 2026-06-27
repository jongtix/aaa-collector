package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.market.enums.IndicatorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IndicatorCodeConverterTest {

    private final IndicatorCodeConverter converter = new IndicatorCodeConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(IndicatorCode.USDKRW)).isEqualTo("USDKRW");
            assertThat(converter.convertToDatabaseColumn(IndicatorCode.VIX)).isEqualTo("VIX");
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
        void 저장문자열을_상수로_변환한다() {
            assertThat(converter.convertToEntityAttribute("USDKRW"))
                    .isEqualTo(IndicatorCode.USDKRW);
            assertThat(converter.convertToEntityAttribute("VIX")).isEqualTo(IndicatorCode.VIX);
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
