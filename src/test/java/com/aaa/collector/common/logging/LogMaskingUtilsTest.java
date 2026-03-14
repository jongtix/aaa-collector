package com.aaa.collector.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LogMaskingUtils")
class LogMaskingUtilsTest {

    @Nested
    @DisplayName("maskFrontOnly()")
    class MaskFrontOnlyTest {

        @Test
        @DisplayName("null 값은 그대로 반환한다")
        void nullReturnsNull() {
            assertThat(LogMaskingUtils.maskFrontOnly(null)).isNull();
        }

        @Test
        @DisplayName("빈 문자열은 그대로 반환한다")
        void emptyStringReturnsEmpty() {
            assertThat(LogMaskingUtils.maskFrontOnly("")).isEqualTo("");
        }

        @Test
        @DisplayName("blank 값은 그대로 반환한다")
        void blankReturnsBlank() {
            assertThat(LogMaskingUtils.maskFrontOnly("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("4자 미만이면 ****로 전체 마스킹한다")
        void shortValueReturnsMasked() {
            assertThat(LogMaskingUtils.maskFrontOnly("abc")).isEqualTo("****");
        }

        @Test
        @DisplayName("정확히 4자이면 앞 4자 + ****를 반환한다")
        void exactlyFourCharsReturnsFrontMasked() {
            assertThat(LogMaskingUtils.maskFrontOnly("abcd")).isEqualTo("abcd****");
        }

        @Test
        @DisplayName("4자 초과이면 앞 4자 + ****를 반환한다")
        void longValueReturnsFrontMasked() {
            assertThat(LogMaskingUtils.maskFrontOnly("abcdefgh")).isEqualTo("abcd****");
        }
    }

    @Nested
    @DisplayName("maskFrontBack()")
    class MaskFrontBackTest {

        @Test
        @DisplayName("null 값은 그대로 반환한다")
        void nullReturnsNull() {
            assertThat(LogMaskingUtils.maskFrontBack(null)).isNull();
        }

        @Test
        @DisplayName("빈 문자열은 그대로 반환한다")
        void emptyStringReturnsEmpty() {
            assertThat(LogMaskingUtils.maskFrontBack("")).isEqualTo("");
        }

        @Test
        @DisplayName("blank 값은 그대로 반환한다")
        void blankReturnsBlank() {
            assertThat(LogMaskingUtils.maskFrontBack("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("8자 미만이면 ****로 전체 마스킹한다")
        void shortValueReturnsMasked() {
            assertThat(LogMaskingUtils.maskFrontBack("abc")).isEqualTo("****");
        }

        @Test
        @DisplayName("정확히 8자이면 앞 4자 + **** + 뒤 4자를 반환한다")
        void exactlyEightCharsReturnsFrontBackMasked() {
            assertThat(LogMaskingUtils.maskFrontBack("abcd1234")).isEqualTo("abcd****1234");
        }

        @Test
        @DisplayName("8자 초과이면 앞 4자 + **** + 뒤 4자를 반환한다")
        void longValueReturnsFrontBackMasked() {
            assertThat(LogMaskingUtils.maskFrontBack("PSKd12345678q1xG")).isEqualTo("PSKd****q1xG");
        }
    }

    @Nested
    @DisplayName("maskBackOnly()")
    class MaskBackOnlyTest {

        @Test
        @DisplayName("null 값은 그대로 반환한다")
        void nullReturnsNull() {
            assertThat(LogMaskingUtils.maskBackOnly(null)).isNull();
        }

        @Test
        @DisplayName("빈 문자열은 그대로 반환한다")
        void emptyStringReturnsEmpty() {
            assertThat(LogMaskingUtils.maskBackOnly("")).isEqualTo("");
        }

        @Test
        @DisplayName("blank 값은 그대로 반환한다")
        void blankReturnsBlank() {
            assertThat(LogMaskingUtils.maskBackOnly("   ")).isEqualTo("   ");
        }

        @Test
        @DisplayName("1자이면 ****로 전체 마스킹한다")
        void oneCharReturnsMasked() {
            assertThat(LogMaskingUtils.maskBackOnly("a")).isEqualTo("****");
        }

        @Test
        @DisplayName("정확히 2자이면 ****로 전체 마스킹한다")
        void exactlyTwoCharsReturnsMasked() {
            assertThat(LogMaskingUtils.maskBackOnly("01")).isEqualTo("****");
        }

        @Test
        @DisplayName("3자(최솟값)이면 ****와 뒤 2자를 반환한다")
        void threeCharsReturnsBackOnly() {
            assertThat(LogMaskingUtils.maskBackOnly("abc")).isEqualTo("****bc");
        }

        @Test
        @DisplayName("3자 초과이면 ****와 뒤 2자를 반환한다")
        void longValueReturnsBackOnly() {
            assertThat(LogMaskingUtils.maskBackOnly("1234567890")).isEqualTo("****90");
        }
    }

    @Nested
    @DisplayName("mask() - FRONT_ONLY 레벨 키 자동 디스패치")
    class MaskFrontOnlyDispatchTest {

        @Test
        @DisplayName("appsecret은 FRONT_ONLY로 마스킹한다")
        void appsecretMasksFrontOnly() {
            assertThat(LogMaskingUtils.mask("appsecret", "s3cretValue")).isEqualTo("s3cr****");
        }
    }

    @Nested
    @DisplayName("mask() - FRONT_BACK 레벨 키 자동 디스패치")
    class MaskFrontBackDispatchTest {

        @Test
        @DisplayName("appkey는 FRONT_BACK으로 마스킹한다")
        void appkeyMasksFrontBack() {
            assertThat(LogMaskingUtils.mask("appkey", "PSKd12345678q1xG"))
                    .isEqualTo("PSKd****q1xG");
        }

        @Test
        @DisplayName("bearerToken은 FRONT_BACK으로 마스킹한다")
        void bearerTokenMasksFrontBack() {
            assertThat(LogMaskingUtils.mask("bearerToken", "eyJhbGciOiJab3c"))
                    .isEqualTo("eyJh****ab3c");
        }

        @Test
        @DisplayName("wsKey는 FRONT_BACK으로 마스킹한다")
        void wsKeyMasksFrontBack() {
            assertThat(LogMaskingUtils.mask("wsKey", "abcd56781234efgh")).isEqualTo("abcd****efgh");
        }
    }

    @Nested
    @DisplayName("mask() - BACK_ONLY 레벨 키 자동 디스패치")
    class MaskBackOnlyDispatchTest {

        @Test
        @DisplayName("accountNo는 BACK_ONLY로 마스킹한다")
        void accountNoMasksBackOnly() {
            assertThat(LogMaskingUtils.mask("accountNo", "1234567890")).isEqualTo("****90");
        }
    }

    @Nested
    @DisplayName("mask() - 미등록 키")
    class MaskUnknownKeyTest {

        @Test
        @DisplayName("미등록 키는 원본 값을 반환한다")
        void unknownKeyReturnsOriginal() {
            assertThat(LogMaskingUtils.mask("userId", "user-123")).isEqualTo("user-123");
        }

        @Test
        @DisplayName("trace_id는 원본 값을 반환한다")
        void traceIdReturnsOriginal() {
            assertThat(LogMaskingUtils.mask("trace_id", "abc-def")).isEqualTo("abc-def");
        }
    }

    @Nested
    @DisplayName("mask() - null 키")
    class MaskNullKeyTest {

        @Test
        @DisplayName("null 키는 원본 값을 반환한다")
        void nullKeyReturnsOriginal() {
            assertThat(LogMaskingUtils.mask(null, "someValue")).isEqualTo("someValue");
        }
    }
}
