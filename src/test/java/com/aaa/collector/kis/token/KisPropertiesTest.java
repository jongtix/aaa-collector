package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KisPropertiesTest {

    private static final List<KisAccountCredential> DUMMY_ACCOUNTS =
            List.of(new KisAccountCredential("test", "12345678", "appkey", "appsecret"));
    private static final KisProperties.RateLimit DUMMY_RATE_LIMIT =
            new KisProperties.RateLimit(20, 20, 10);

    @Test
    @DisplayName("baseUrl이 https://로 시작하면 정상 생성된다")
    void constructor_withHttpsBaseUrl_createsSuccessfully() {
        KisProperties props =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "user",
                        DUMMY_ACCOUNTS,
                        DUMMY_RATE_LIMIT);

        assertThat(props.baseUrl()).isEqualTo("https://openapi.koreainvestment.com");
    }

    @Test
    @DisplayName("baseUrl이 http://로 시작하면 IllegalArgumentException이 발생한다")
    void constructor_withHttpBaseUrl_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "http://openapi.koreainvestment.com",
                                        "user",
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme");
    }

    @Test
    @DisplayName("baseUrl이 null이면 IllegalArgumentException이 발생한다")
    void constructor_withNullBaseUrl_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new KisProperties(null, "user", DUMMY_ACCOUNTS, DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme");
    }

    @Test
    @DisplayName("baseUrl이 빈 문자열이면 IllegalArgumentException이 발생한다")
    void constructor_withEmptyBaseUrl_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new KisProperties("", "user", DUMMY_ACCOUNTS, DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme");
    }

    @Test
    @DisplayName("baseUrl이 https로 시작하지만 ://가 없으면 IllegalArgumentException이 발생한다")
    void constructor_withHttpsWithoutSchemeDelimiter_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https-not-scheme",
                                        "user",
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme");
    }

    @Test
    @DisplayName("userId가 null이면 IllegalArgumentException이 발생한다")
    void constructor_withNullUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        null,
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user-id");
    }

    @Test
    @DisplayName("userId가 blank이면 IllegalArgumentException이 발생한다")
    void constructor_withBlankUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "   ",
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user-id");
    }

    @Test
    @DisplayName("accounts가 null이면 IllegalArgumentException이 발생한다")
    void constructor_withNullAccounts_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "user",
                                        null,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accounts");
    }

    @Test
    @DisplayName("accounts가 빈 리스트이면 IllegalArgumentException이 발생한다")
    void constructor_withEmptyAccounts_throwsIllegalArgumentException() {
        List<KisAccountCredential> emptyList = List.of();
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "user",
                                        emptyList,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accounts");
    }

    @Test
    @DisplayName("accounts는 방어 복사되어 불변 리스트로 저장된다")
    void constructor_accountsIsDefensivelyCopied() {
        // Arrange
        List<KisAccountCredential> mutableList = new ArrayList<>(DUMMY_ACCOUNTS);

        // Act
        KisProperties props =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "user",
                        mutableList,
                        DUMMY_RATE_LIMIT);
        mutableList.clear();

        // Assert: 원본 리스트 변경이 props.accounts()에 영향을 주지 않아야 함
        assertThat(props.accounts()).hasSize(1);
    }

    @Test
    @DisplayName("userId가 빈 문자열이면 IllegalArgumentException이 발생한다")
    void constructor_withEmptyUserId_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "",
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user-id");
    }

    @Test
    @DisplayName("baseUrl과 userId가 동시에 잘못되면 두 위반이 모두 에러 메시지에 포함된다")
    void constructor_withInvalidBaseUrlAndUserId_errorMessageContainsBothViolations() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "http://openapi.koreainvestment.com",
                                        null,
                                        DUMMY_ACCOUNTS,
                                        DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme")
                .hasMessageContaining("user-id");
    }

    @Test
    @DisplayName("3개 필드가 전부 잘못되면 세 위반이 모두 에러 메시지에 포함된다")
    void constructor_withAllInvalidFields_errorMessageContainsAllViolations() {
        assertThatThrownBy(() -> new KisProperties(null, null, null, DUMMY_RATE_LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme")
                .hasMessageContaining("user-id")
                .hasMessageContaining("accounts");
    }

    @Test
    @DisplayName("4개 필드가 전부 잘못되면 네 위반이 모두 에러 메시지에 포함된다")
    void constructor_withAllInvalidFieldsIncludingRateLimit_errorMessageContainsAllViolations() {
        assertThatThrownBy(() -> new KisProperties(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS scheme")
                .hasMessageContaining("user-id")
                .hasMessageContaining("accounts")
                .hasMessageContaining("rate-limit");
    }

    @Test
    @DisplayName("accounts()가 반환하는 리스트는 불변이다")
    void accounts_returnsUnmodifiableList() {
        KisProperties props =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "user",
                        DUMMY_ACCOUNTS,
                        DUMMY_RATE_LIMIT);

        List<KisAccountCredential> accounts = props.accounts();
        KisAccountCredential element = DUMMY_ACCOUNTS.getFirst();
        assertThatThrownBy(() -> accounts.add(element))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("rateLimit이 null이면 IllegalArgumentException이 발생한다")
    void constructor_withNullRateLimit_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "user",
                                        DUMMY_ACCOUNTS,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate-limit");
    }

    @Test
    @DisplayName("rateLimit.capacity가 0이면 IllegalArgumentException이 발생한다")
    void constructor_withZeroCapacity_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "user",
                                        DUMMY_ACCOUNTS,
                                        new KisProperties.RateLimit(0, 20, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate-limit.capacity");
    }

    @Test
    @DisplayName("rateLimit.refillPerSecond가 0이면 IllegalArgumentException이 발생한다")
    void constructor_withZeroRefillPerSecond_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new KisProperties(
                                        "https://openapi.koreainvestment.com",
                                        "user",
                                        DUMMY_ACCOUNTS,
                                        new KisProperties.RateLimit(20, 0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate-limit.refill-per-second");
    }
}
