package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BackfillStatusConverterTest {

    private final BackfillStatusConverter converter = new BackfillStatusConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        void 모든_상수를_name으로_변환한다() {
            // Act & Assert
            assertThat(converter.convertToDatabaseColumn(BackfillStatusType.PENDING))
                    .isEqualTo("PENDING");
            assertThat(converter.convertToDatabaseColumn(BackfillStatusType.IN_PROGRESS))
                    .isEqualTo("IN_PROGRESS");
            assertThat(converter.convertToDatabaseColumn(BackfillStatusType.COMPLETED))
                    .isEqualTo("COMPLETED");
            assertThat(converter.convertToDatabaseColumn(BackfillStatusType.FAILED))
                    .isEqualTo("FAILED");
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
            assertThat(converter.convertToEntityAttribute("PENDING"))
                    .isEqualTo(BackfillStatusType.PENDING);
            assertThat(converter.convertToEntityAttribute("IN_PROGRESS"))
                    .isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(converter.convertToEntityAttribute("COMPLETED"))
                    .isEqualTo(BackfillStatusType.COMPLETED);
            assertThat(converter.convertToEntityAttribute("FAILED"))
                    .isEqualTo(BackfillStatusType.FAILED);
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
