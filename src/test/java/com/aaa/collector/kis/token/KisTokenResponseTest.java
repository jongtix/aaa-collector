package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.common.logging.LogMaskingUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KisTokenResponseTest {

    private static final String ACCESS_TOKEN = "test-access-token-value";
    private static final String TOKEN_TYPE = "Bearer";
    private static final int EXPIRES_IN = 86_400;
    private static final String ACCESS_TOKEN_TOKEN_EXPIRED = "2026-03-21 00:00:00";

    @Nested
    @DisplayName("toString() 마스킹")
    class ToStringMasking {

        @Test
        @DisplayName("toString() 결과에 원본 accessToken 값이 포함되지 않는다")
        void toString_doesNotExposeAccessToken() {
            KisTokenResponse response =
                    new KisTokenResponse(
                            ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN, ACCESS_TOKEN_TOKEN_EXPIRED);

            assertThat(response.toString()).doesNotContain(ACCESS_TOKEN);
        }

        @Test
        @DisplayName("toString() 결과에 maskFrontBack(accessToken) 결과가 포함된다")
        void toString_containsMaskedAccessToken() {
            KisTokenResponse response =
                    new KisTokenResponse(
                            ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN, ACCESS_TOKEN_TOKEN_EXPIRED);

            assertThat(response.toString()).contains(LogMaskingUtils.maskFrontBack(ACCESS_TOKEN));
        }
    }

    @Nested
    @DisplayName("toString() 비민감 필드 노출")
    class ToStringNonSensitiveFields {

        @Test
        @DisplayName("toString() 결과에 tokenType이 평문으로 포함된다")
        void toString_exposesTokenType() {
            KisTokenResponse response =
                    new KisTokenResponse(
                            ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN, ACCESS_TOKEN_TOKEN_EXPIRED);

            assertThat(response.toString()).contains(TOKEN_TYPE);
        }

        @Test
        @DisplayName("toString() 결과에 expiresIn이 평문으로 포함된다")
        void toString_exposesExpiresIn() {
            KisTokenResponse response =
                    new KisTokenResponse(
                            ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN, ACCESS_TOKEN_TOKEN_EXPIRED);

            assertThat(response.toString()).contains(String.valueOf(EXPIRES_IN));
        }

        @Test
        @DisplayName("toString() 결과에 accessTokenTokenExpired가 평문으로 포함된다")
        void toString_exposesAccessTokenTokenExpired() {
            KisTokenResponse response =
                    new KisTokenResponse(
                            ACCESS_TOKEN, TOKEN_TYPE, EXPIRES_IN, ACCESS_TOKEN_TOKEN_EXPIRED);

            assertThat(response.toString()).contains(ACCESS_TOKEN_TOKEN_EXPIRED);
        }
    }
}
