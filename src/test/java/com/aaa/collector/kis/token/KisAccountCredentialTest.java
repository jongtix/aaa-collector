package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.common.logging.LogMaskingUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KisAccountCredentialTest {

    private static final String VALID_ALIAS = "isa";
    private static final String VALID_ACCOUNT_NUMBER = "12345678";
    private static final String VALID_APP_KEY = "app-key-value";
    private static final String VALID_APP_SECRET = "app-secret-value";

    @Test
    @DisplayName("모든 필드가 유효하면 정상 생성된다")
    void constructor_withValidFields_createsSuccessfully() {
        KisAccountCredential credential =
                new KisAccountCredential(
                        VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

        assertThat(credential.alias()).isEqualTo(VALID_ALIAS);
        assertThat(credential.accountNumber()).isEqualTo(VALID_ACCOUNT_NUMBER);
        assertThat(credential.appKey()).isEqualTo(VALID_APP_KEY);
        assertThat(credential.appSecret()).isEqualTo(VALID_APP_SECRET);
    }

    @ParameterizedTest(name = "alias=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("alias가 null 또는 blank이면 IllegalArgumentException이 발생한다")
    void constructor_withInvalidAlias_throwsIllegalArgumentException(String alias) {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        alias,
                                        VALID_ACCOUNT_NUMBER,
                                        VALID_APP_KEY,
                                        VALID_APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }

    @ParameterizedTest(name = "accountNumber=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("accountNumber가 null 또는 blank이면 IllegalArgumentException이 발생한다")
    void constructor_withInvalidAccountNumber_throwsIllegalArgumentException(String accountNumber) {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        VALID_ALIAS,
                                        accountNumber,
                                        VALID_APP_KEY,
                                        VALID_APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountNumber");
    }

    @ParameterizedTest(name = "appKey=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("appKey가 null 또는 blank이면 IllegalArgumentException이 발생한다")
    void constructor_withInvalidAppKey_throwsIllegalArgumentException(String appKey) {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        VALID_ALIAS,
                                        VALID_ACCOUNT_NUMBER,
                                        appKey,
                                        VALID_APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appKey")
                .hasMessageNotContaining(VALID_APP_SECRET);
    }

    @ParameterizedTest(name = "appSecret=\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("appSecret가 null 또는 blank이면 IllegalArgumentException이 발생한다")
    void constructor_withInvalidAppSecret_throwsIllegalArgumentException(String appSecret) {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        VALID_ALIAS,
                                        VALID_ACCOUNT_NUMBER,
                                        VALID_APP_KEY,
                                        appSecret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appSecret")
                .hasMessageNotContaining(VALID_APP_KEY);
    }

    @Test
    @DisplayName("에러 메시지에 appKey 값이 노출되지 않는다")
    void constructor_withBlankAppKey_errorMessageDoesNotExposeValue() {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        VALID_ALIAS, VALID_ACCOUNT_NUMBER, "   ", VALID_APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("   ");
    }

    @Test
    @DisplayName("에러 메시지에 appSecret 값이 노출되지 않는다")
    void constructor_withBlankAppSecret_errorMessageDoesNotExposeValue() {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("   ");
    }

    @Test
    @DisplayName("alias와 appKey가 동시에 null이면 두 위반이 모두 에러 메시지에 포함된다")
    void constructor_withNullAliasAndAppKey_errorMessageContainsBothViolations() {
        assertThatThrownBy(
                        () ->
                                new KisAccountCredential(
                                        null, VALID_ACCOUNT_NUMBER, null, VALID_APP_SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias")
                .hasMessageContaining("appKey");
    }

    @Test
    @DisplayName("4개 필드가 전부 null이면 4개 위반이 모두 에러 메시지에 포함된다")
    void constructor_withAllNullFields_errorMessageContainsAllViolations() {
        assertThatThrownBy(() -> new KisAccountCredential(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias")
                .hasMessageContaining("accountNumber")
                .hasMessageContaining("appKey")
                .hasMessageContaining("appSecret");
    }

    @Nested
    @DisplayName("toString() 마스킹")
    class ToStringMasking {

        @Test
        @DisplayName("toString() 결과에 원본 appKey 값이 포함되지 않는다")
        void toString_doesNotExposeAppKey() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.toString()).doesNotContain(VALID_APP_KEY);
        }

        @Test
        @DisplayName("toString() 결과에 원본 appSecret 값이 포함되지 않는다")
        void toString_doesNotExposeAppSecret() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.toString()).doesNotContain(VALID_APP_SECRET);
        }

        @Test
        @DisplayName("toString() 결과에 원본 accountNumber 전체가 포함되지 않는다")
        void toString_doesNotExposeFullAccountNumber() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.toString()).doesNotContain(VALID_ACCOUNT_NUMBER);
        }
    }

    @Nested
    @DisplayName("maskedXxx() 출력 형식")
    class MaskedMethods {

        @Test
        @DisplayName("maskedAccountNumber()는 LogMaskingUtils.maskBackOnly() 결과와 일치한다")
        void maskedAccountNumber_matchesMaskBackOnly() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.maskedAccountNumber())
                    .isEqualTo(LogMaskingUtils.maskBackOnly(VALID_ACCOUNT_NUMBER));
        }

        @Test
        @DisplayName("maskedAppKey()는 LogMaskingUtils.maskFrontBack() 결과와 일치한다")
        void maskedAppKey_matchesMaskFrontBack() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.maskedAppKey())
                    .isEqualTo(LogMaskingUtils.maskFrontBack(VALID_APP_KEY));
        }

        @Test
        @DisplayName("maskedAppSecret()는 LogMaskingUtils.maskFrontOnly() 결과와 일치한다")
        void maskedAppSecret_matchesMaskFrontOnly() {
            KisAccountCredential credential =
                    new KisAccountCredential(
                            VALID_ALIAS, VALID_ACCOUNT_NUMBER, VALID_APP_KEY, VALID_APP_SECRET);

            assertThat(credential.maskedAppSecret())
                    .isEqualTo(LogMaskingUtils.maskFrontOnly(VALID_APP_SECRET));
        }
    }
}
