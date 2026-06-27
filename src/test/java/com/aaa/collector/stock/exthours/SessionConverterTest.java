package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SessionConverterTest {

    private final SessionConverter converter = new SessionConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(Session.PRE)).isEqualTo("PRE");
            assertThat(converter.convertToDatabaseColumn(Session.AFTER)).isEqualTo("AFTER");
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
            assertThat(converter.convertToEntityAttribute("PRE")).isEqualTo(Session.PRE);
            assertThat(converter.convertToEntityAttribute("AFTER")).isEqualTo(Session.AFTER);
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
