package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FinraQuantityParser — 소수 보존 변환 (SPEC-COLLECTOR-SHORTSALE-DECIMAL-001)")
class FinraQuantityParserTest {

    @Nested
    @DisplayName("toNonNegativeDecimal — Daily 거래량 소수 보존 (REQ-SSD-006/010/011/012)")
    class ToNonNegativeDecimal {

        @Test
        @DisplayName("정상 정수 BigDecimal은 그대로 반환한다")
        void convertsValidInteger() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            BigDecimal.valueOf(134_422_787L), "qty", reasons);

            assertThat(result).isEqualByComparingTo("134422787");
            assertThat(reasons).isEmpty();
        }

        @Test
        @DisplayName("소수부 6자리 값을 무손실 그대로 보존한다 (2026-02-23 FINRA 소수 전환, REQ-SSD-006)")
        void preservesSixDecimalDigitsLosslessly() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            new BigDecimal("11479561.984835"), "qty", reasons);

            // 반올림·절삭 없이 원천 정밀도(6자리) 그대로 보존
            assertThat(result).isEqualByComparingTo("11479561.984835");
            assertThat(result.stripTrailingZeros().scale()).isEqualTo(6);
            assertThat(reasons).isEmpty();
        }

        @Test
        @DisplayName("소수부가 있어도(10.5) skip하지 않고 무손실 보존한다 (기존 거부 동작 flip)")
        void preservesFractionalInsteadOfRejecting() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            new BigDecimal("10.5"), "qty", reasons);

            assertThat(result).isEqualByComparingTo("10.5");
            assertThat(reasons).isEmpty();
        }

        @Test
        @DisplayName("null 값은 null 반환하고 reasons에 사유를 누적한다")
        void returnsNullOnNull() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result = FinraQuantityParser.toNonNegativeDecimal(null, "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).containsExactly("qty=null");
        }

        @Test
        @DisplayName("음수 값은 null 반환하고 reasons에 사유를 누적한다 (음수는 여전히 무효, REQ-SSD-010)")
        void returnsNullOnNegative() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            BigDecimal.valueOf(-1), "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).containsExactly("qty<0(-1)");
        }

        @Test
        @DisplayName("scale(6)을 초과하는 소수 자릿수는 fail-loud 거부한다 (조용한 반올림 금지, REQ-SSD-011)")
        void returnsNullOnScaleOverflow() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            new BigDecimal("1.1234567"), "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).containsExactly("qty scale 초과(1.1234567)");
        }

        @Test
        @DisplayName("후행 0은 유효 자릿수로 세지 않는다 (1.0000000 은 scale 0으로 허용)")
        void trailingZerosDoNotCountAsScale() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            new BigDecimal("1.0000000"), "qty", reasons);

            assertThat(result).isEqualByComparingTo("1");
            assertThat(reasons).isEmpty();
        }

        @Test
        @DisplayName("극단적 지수 입력(stripTrailingZeros scale 오버플로)은 예외 없이 fail-loud 거부한다")
        void returnsNullOnExtremeExponentWithoutThrowing() {
            List<String> reasons = new ArrayList<>();
            BigDecimal result =
                    FinraQuantityParser.toNonNegativeDecimal(
                            new BigDecimal("100E2147483647"), "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).hasSize(1);
            assertThat(reasons.getFirst()).startsWith("qty scale 오버플로");
        }
    }

    @Nested
    @DisplayName("toNonNegativeInteger — Short Interest 정수 검증 래퍼 (REQ-SSD-016)")
    class ToNonNegativeInteger {

        @Test
        @DisplayName("정상 정수는 long으로 변환한다")
        void convertsValidInteger() {
            List<String> reasons = new ArrayList<>();
            Long result =
                    FinraQuantityParser.toNonNegativeInteger(
                            BigDecimal.valueOf(134_422_787L), "qty", reasons);

            assertThat(result).isEqualTo(134_422_787L);
            assertThat(reasons).isEmpty();
        }

        @Test
        @DisplayName("소수부가 있으면 조용히 버리지 않고 거부 사유를 누적한다 (침묵 skip 방지, REQ-SSD-016)")
        void rejectsFractionalAsSignalNotSilentDrop() {
            List<String> reasons = new ArrayList<>();
            Long result =
                    FinraQuantityParser.toNonNegativeInteger(
                            new BigDecimal("10.5"), "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).containsExactly("qty 정수 아님·소수부 존재(10.5)");
        }

        @Test
        @DisplayName("음수는 null 반환하고 사유를 누적한다")
        void returnsNullOnNegative() {
            List<String> reasons = new ArrayList<>();
            Long result =
                    FinraQuantityParser.toNonNegativeInteger(
                            BigDecimal.valueOf(-1), "qty", reasons);

            assertThat(result).isNull();
            assertThat(reasons).containsExactly("qty<0(-1)");
        }
    }
}
