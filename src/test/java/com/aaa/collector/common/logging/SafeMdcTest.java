package com.aaa.collector.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("SafeMdc")
class SafeMdcTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("put() — 민감 키 자동 마스킹")
    class PutSensitiveKeyTest {

        @Test
        @DisplayName("appsecret은 FRONT_ONLY로 마스킹되어 MDC에 저장된다")
        void appsecretIsMaskedFrontOnly() {
            SafeMdc.put("appsecret", "s3cretValue");
            assertThat(MDC.get("appsecret")).isEqualTo("s3cr****");
        }

        @Test
        @DisplayName("appkey는 FRONT_BACK으로 마스킹되어 MDC에 저장된다")
        void appkeyIsMaskedFrontBack() {
            SafeMdc.put("appkey", "PSKd12345678q1xG");
            assertThat(MDC.get("appkey")).isEqualTo("PSKd****q1xG");
        }

        @Test
        @DisplayName("bearerToken은 FRONT_BACK으로 마스킹되어 MDC에 저장된다")
        void bearerTokenIsMaskedFrontBack() {
            SafeMdc.put("bearerToken", "eyJhbGciOiJab3c");
            assertThat(MDC.get("bearerToken")).isEqualTo("eyJh****ab3c");
        }

        @Test
        @DisplayName("wsKey는 FRONT_BACK으로 마스킹되어 MDC에 저장된다")
        void wsKeyIsMaskedFrontBack() {
            SafeMdc.put("wsKey", "abcd56781234efgh");
            assertThat(MDC.get("wsKey")).isEqualTo("abcd****efgh");
        }

        @Test
        @DisplayName("accountNo는 BACK_ONLY로 마스킹되어 MDC에 저장된다")
        void accountNoIsMaskedBackOnly() {
            SafeMdc.put("accountNo", "1234567890");
            assertThat(MDC.get("accountNo")).isEqualTo("****90");
        }
    }

    @Nested
    @DisplayName("put() — 미등록 키는 원본 값 저장")
    class PutUnregisteredKeyTest {

        @Test
        @DisplayName("미등록 키는 원본 값 그대로 MDC에 저장된다")
        void unknownKeyStoresOriginalValue() {
            SafeMdc.put("userId", "user-123");
            assertThat(MDC.get("userId")).isEqualTo("user-123");
        }

        @Test
        @DisplayName("trace_id는 원본 값 그대로 MDC에 저장된다")
        void traceIdStoresOriginalValue() {
            SafeMdc.put("trace_id", "550e8400-e29b-41d4-a716-446655440000");
            assertThat(MDC.get("trace_id")).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        }
    }

    @Nested
    @DisplayName("put() — null 값 처리")
    class PutNullValueTest {

        @Test
        @DisplayName("민감 키에 null 값을 넣으면 MDC에 null이 저장된다")
        void sensitiveKeyWithNullValueStoresNull() {
            SafeMdc.put("appsecret", null);
            assertThat(MDC.get("appsecret")).isNull();
        }

        @Test
        @DisplayName("미등록 키에 null 값을 넣으면 MDC에 null이 저장된다")
        void unknownKeyWithNullValueStoresNull() {
            SafeMdc.put("userId", null);
            assertThat(MDC.get("userId")).isNull();
        }
    }

    @Nested
    @DisplayName("put() — 잘못된 키 검증")
    class PutInvalidKeyTest {

        @Test
        @DisplayName("null 키로 put()을 호출하면 IllegalArgumentException이 발생한다")
        void nullKeyThrowsIllegalArgumentException() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SafeMdc.put(null, "someValue"))
                    .withMessage("MDC key must not be null");
        }

        @Test
        @DisplayName("빈 문자열 키는 MDC에 그대로 저장된다")
        void blankKeyStoresValueAsIs() {
            SafeMdc.put("", "someValue");
            assertThat(MDC.get("")).isEqualTo("someValue");
        }
    }

    @Nested
    @DisplayName("remove()")
    class RemoveTest {

        @Test
        @DisplayName("remove()는 MDC에서 해당 키를 제거한다")
        void removesKeyFromMdc() {
            SafeMdc.put("userId", "user-123");
            SafeMdc.remove("userId");
            assertThat(MDC.get("userId")).isNull();
        }

        @Test
        @DisplayName("remove()는 다른 MDC 키에 영향을 주지 않는다")
        void removeDoesNotAffectOtherKeys() {
            SafeMdc.put("userId", "user-123");
            SafeMdc.put("trace_id", "abc-def");
            SafeMdc.remove("userId");
            assertThat(MDC.get("trace_id")).isEqualTo("abc-def");
        }

        @Test
        @DisplayName("null 키로 remove()를 호출하면 IllegalArgumentException이 발생한다")
        void nullKeyThrowsIllegalArgumentException() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SafeMdc.remove(null))
                    .withMessage("MDC key must not be null");
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTest {

        @Test
        @DisplayName("clear()는 MDC의 모든 키를 제거한다")
        void clearsAllMdcKeys() {
            SafeMdc.put("userId", "user-123");
            SafeMdc.put("trace_id", "abc-def");
            SafeMdc.clear();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("clear() 후 MDC는 비어 있다")
        void mdcIsEmptyAfterClear() {
            SafeMdc.put("appsecret", "s3cretValue");
            SafeMdc.clear();
            assertThat(MDC.get("appsecret")).isNull();
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTest {

        @Test
        @DisplayName("get()은 MDC에 저장된 값을 반환한다")
        void returnsStoredValue() {
            SafeMdc.put("userId", "user-123");
            assertThat(SafeMdc.get("userId")).isEqualTo("user-123");
        }

        @Test
        @DisplayName("존재하지 않는 키에 대해 get()은 null을 반환한다")
        void returnsNullForAbsentKey() {
            assertThat(SafeMdc.get("nonExistentKey")).isNull();
        }

        @Test
        @DisplayName("민감 키의 get()은 마스킹된 값을 반환한다")
        void sensitiveKeyGetReturnsMaskedValue() {
            SafeMdc.put("appsecret", "s3cretValue");
            assertThat(SafeMdc.get("appsecret")).isEqualTo("s3cr****");
        }
    }
}
