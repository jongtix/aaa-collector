package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.stock.enums.PeriodType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PeriodTypeConverterTest {

    private final PeriodTypeConverter converter = new PeriodTypeConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(PeriodType.ANNUAL)).isEqualTo("ANNUAL");
            assertThat(converter.convertToDatabaseColumn(PeriodType.QUARTERLY))
                    .isEqualTo("QUARTERLY");
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
            assertThat(converter.convertToEntityAttribute("ANNUAL")).isEqualTo(PeriodType.ANNUAL);
            assertThat(converter.convertToEntityAttribute("QUARTERLY"))
                    .isEqualTo(PeriodType.QUARTERLY);
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
