package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.stock.enums.DelistingReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

// @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
class DelistingReasonConverterTest {

    private final DelistingReasonConverter converter = new DelistingReasonConverter();

    @Nested
    @DisplayName("convertToDatabaseColumn")
    class ConvertToDatabaseColumn {

        @Test
        @DisplayName("상수를 name()으로 변환한다 — 저장값 == enum.name() (REQ-WLSYNC-140)")
        void 상수를_name으로_변환한다() {
            assertThat(converter.convertToDatabaseColumn(DelistingReason.BANKRUPTCY))
                    .isEqualTo("BANKRUPTCY");
            assertThat(converter.convertToDatabaseColumn(DelistingReason.MERGER))
                    .isEqualTo("MERGER");
            assertThat(converter.convertToDatabaseColumn(DelistingReason.VOLUNTARY))
                    .isEqualTo("VOLUNTARY");
            assertThat(converter.convertToDatabaseColumn(DelistingReason.ETF_TERMINATION))
                    .isEqualTo("ETF_TERMINATION");
            assertThat(converter.convertToDatabaseColumn(DelistingReason.UNKNOWN))
                    .isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("null 입력 시 null을 반환한다")
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }
    }

    @Nested
    @DisplayName("convertToEntityAttribute")
    class ConvertToEntityAttribute {

        @Test
        @DisplayName("저장 문자열을 상수로 변환한다 — 라운드트립")
        void 저장문자열을_상수로_변환한다() {
            assertThat(converter.convertToEntityAttribute("BANKRUPTCY"))
                    .isEqualTo(DelistingReason.BANKRUPTCY);
            assertThat(converter.convertToEntityAttribute("UNKNOWN"))
                    .isEqualTo(DelistingReason.UNKNOWN);
        }

        @Test
        @DisplayName("null 입력 시 null을 반환한다")
        void null_입력시_null을_반환한다() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }

        @Test
        @DisplayName("미지값이면 IllegalArgumentException을 던진다")
        void 미지값이면_IllegalArgumentException을_던진다() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("NOT_A_REASON"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
