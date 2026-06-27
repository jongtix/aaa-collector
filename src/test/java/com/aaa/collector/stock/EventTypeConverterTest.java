package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.stock.enums.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventTypeConverterTest {

    private final EventTypeConverter converter = new EventTypeConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(EventType.DIVIDEND)).isEqualTo("DIVIDEND");
            assertThat(converter.convertToDatabaseColumn(EventType.RIGHTS_ISSUE))
                    .isEqualTo("RIGHTS_ISSUE");
            assertThat(converter.convertToDatabaseColumn(EventType.SPLIT)).isEqualTo("SPLIT");
            assertThat(converter.convertToDatabaseColumn(EventType.EARNINGS)).isEqualTo("EARNINGS");
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
            assertThat(converter.convertToEntityAttribute("DIVIDEND"))
                    .isEqualTo(EventType.DIVIDEND);
            assertThat(converter.convertToEntityAttribute("RIGHTS_ISSUE"))
                    .isEqualTo(EventType.RIGHTS_ISSUE);
            assertThat(converter.convertToEntityAttribute("SPLIT")).isEqualTo(EventType.SPLIT);
            assertThat(converter.convertToEntityAttribute("EARNINGS"))
                    .isEqualTo(EventType.EARNINGS);
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
