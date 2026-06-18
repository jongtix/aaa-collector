package com.aaa.collector.stock.fundamental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FundamentalValueParser 단위 테스트 (REQ-BATCH4-070a)")
class FundamentalValueParserTest {

    @Nested
    @DisplayName("parseBigInt — .00 무손실 정수 변환 (AC-FIN-9)")
    class ParseBigInt {

        @Test
        @DisplayName("\".00\" 소수 접미사 — 무손실 정수 변환")
        void decimalSuffix_lossless() {
            assertThat(FundamentalValueParser.parseBigInt("6993.00")).isEqualTo(6993L);
            assertThat(FundamentalValueParser.parseBigInt("71907.00")).isEqualTo(71_907L);
        }

        @Test
        @DisplayName("정수 문자열 — 그대로 변환")
        void plainInteger() {
            assertThat(FundamentalValueParser.parseBigInt("57655")).isEqualTo(57_655L);
        }

        @Test
        @DisplayName("앞뒤 공백 — trim 후 변환")
        void trimmed() {
            assertThat(FundamentalValueParser.parseBigInt("  6993.00  ")).isEqualTo(6993L);
        }

        @Test
        @DisplayName("비0 소수부(6993.50) — ArithmeticException (건별 skip 유발)")
        void nonZeroFraction_throwsArithmetic() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(
                            () ->
                                    assertThat(FundamentalValueParser.parseBigInt("6993.50"))
                                            .isZero());
        }

        @Test
        @DisplayName("long 범위 초과 — ArithmeticException")
        void outOfRange_throwsArithmetic() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(
                            () ->
                                    assertThat(
                                                    FundamentalValueParser.parseBigInt(
                                                            "99999999999999999999"))
                                            .isZero());
        }

        @Test
        @DisplayName("파싱 불가 문자열 — NumberFormatException")
        void unparseable_throwsNumberFormat() {
            assertThatThrownBy(() -> assertThat(FundamentalValueParser.parseBigInt("abc")).isZero())
                    .isInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("null 입력 — NumberFormatException (NPE 아님, 건별 skip 경로 라우팅 MI-1)")
        void null_throwsNumberFormat() {
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(
                            () -> assertThat(FundamentalValueParser.parseBigInt(null)).isZero());
        }

        @Test
        @DisplayName("blank 입력(공백만) — NumberFormatException (NPE 아님, 건별 skip 경로 라우팅 MI-1)")
        void blank_throwsNumberFormat() {
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(
                            () -> assertThat(FundamentalValueParser.parseBigInt("  ")).isZero());
        }
    }

    @Nested
    @DisplayName("parseDecimal — DECIMAL(12,4) 경계·부호 (AC-FIN-6/7/8)")
    class ParseDecimal {

        @Test
        @DisplayName("정상 값 — 그대로 반환 (유보비율 1200.5 > 1000도 정상)")
        void normalValue() {
            assertThat(FundamentalValueParser.parseDecimal("1200.5"))
                    .isEqualByComparingTo("1200.5");
        }

        @Test
        @DisplayName("음수 값 — 거부하지 않음 (부호 무거부)")
        void negative_accepted() {
            assertThat(FundamentalValueParser.parseDecimal("-30.5")).isEqualByComparingTo("-30.5");
        }

        @Test
        @DisplayName("소수 5자리 — setScale 없이 원문 보존")
        void fiveDecimalPlaces_preserved() {
            assertThat(FundamentalValueParser.parseDecimal("15.23456"))
                    .isEqualByComparingTo("15.23456");
        }

        @Test
        @DisplayName("정수부 경계 |value| >= 10^8 — ArithmeticException")
        void integerBoundExceeded_throwsArithmetic() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> FundamentalValueParser.parseDecimal("100000000.0"));
        }

        @Test
        @DisplayName("음수 정수부 경계 -10^8 — ArithmeticException")
        void negativeIntegerBoundExceeded_throwsArithmetic() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> FundamentalValueParser.parseDecimal("-100000000.0"));
        }

        @Test
        @DisplayName("경계 직전 값(99999999.9999) — 정상 반환")
        void justBelowBound_accepted() {
            assertThat(FundamentalValueParser.parseDecimal("99999999.9999"))
                    .isEqualByComparingTo(new BigDecimal("99999999.9999"));
        }

        @Test
        @DisplayName("파싱 불가 — NumberFormatException")
        void unparseable_throwsNumberFormat() {
            assertThatThrownBy(() -> FundamentalValueParser.parseDecimal("xyz"))
                    .isInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("null 입력 — NumberFormatException (NPE 아님, 건별 skip 경로 라우팅 MI-1)")
        void null_throwsNumberFormat() {
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(() -> FundamentalValueParser.parseDecimal(null));
        }
    }
}
