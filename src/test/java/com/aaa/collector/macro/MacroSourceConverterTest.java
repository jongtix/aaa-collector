package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.macro.enums.MacroSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MacroSourceConverterTest {

    private final MacroSourceConverter converter = new MacroSourceConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(MacroSource.KIS)).isEqualTo("KIS");
            assertThat(converter.convertToDatabaseColumn(MacroSource.ECOS)).isEqualTo("ECOS");
            assertThat(converter.convertToDatabaseColumn(MacroSource.FRED)).isEqualTo("FRED");
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
            // Act & Assert
            assertThat(converter.convertToEntityAttribute("KIS")).isEqualTo(MacroSource.KIS);
            assertThat(converter.convertToEntityAttribute("ECOS")).isEqualTo(MacroSource.ECOS);
            assertThat(converter.convertToEntityAttribute("FRED")).isEqualTo(MacroSource.FRED);
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
