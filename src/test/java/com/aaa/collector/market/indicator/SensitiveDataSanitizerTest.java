package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SensitiveDataSanitizer 단위 테스트")
class SensitiveDataSanitizerTest {

    @Nested
    @DisplayName("authkey 마스킹")
    class AuthkeyMasking {

        @Test
        @DisplayName("authkey 값을 *** 로 마스킹")
        void masksAuthkey() {
            String msg =
                    "400 Bad Request: [GET https://www.koreaexim.go.kr/exchangeJSON?authkey=MY_SECRET_KEY&searchdate=20260101&data=AP01]";

            String result = SensitiveDataSanitizer.sanitize(msg);

            assertThat(result).contains("authkey=***");
            assertThat(result).doesNotContain("MY_SECRET_KEY");
        }

        @Test
        @DisplayName("대소문자 구분 없이 마스킹 (AUTHKEY)")
        void masksCaseInsensitive() {
            String msg = "Error: AUTHKEY=MY_SECRET_KEY&other=value";

            String result = SensitiveDataSanitizer.sanitize(msg);

            assertThat(result).contains("AUTHKEY=***");
            assertThat(result).doesNotContain("MY_SECRET_KEY");
        }
    }

    @Nested
    @DisplayName("api_key 마스킹")
    class ApiKeyMasking {

        @Test
        @DisplayName("api_key 값을 *** 로 마스킹")
        void masksApiKey() {
            String msg =
                    "503 Service Unavailable: [GET https://api.stlouisfed.org/fred/series/observations?series_id=VIXCLS&api_key=FRED_SECRET_KEY&limit=100000]";

            String result = SensitiveDataSanitizer.sanitize(msg);

            assertThat(result).contains("api_key=***");
            assertThat(result).doesNotContain("FRED_SECRET_KEY");
        }

        @Test
        @DisplayName("URL 뒤 & 이후 파라미터 보존")
        void preservesParametersAfterKey() {
            String msg = "Error url=https://example.com?api_key=SECRET&limit=10";

            String result = SensitiveDataSanitizer.sanitize(msg);

            assertThat(result).contains("api_key=***");
            assertThat(result).contains("limit=10");
            assertThat(result).doesNotContain("SECRET");
        }
    }

    @Nested
    @DisplayName("null 및 민감 정보 없는 경우")
    class EdgeCases {

        @Test
        @DisplayName("null 메시지 — [no message] 반환")
        void nullMessage_returnsPlaceholder() {
            assertThat(SensitiveDataSanitizer.sanitize(null)).isEqualTo("[no message]");
        }

        @Test
        @DisplayName("민감 정보 없는 메시지 — 원본 그대로 반환")
        void noSensitiveData_returnsSame() {
            String msg = "Connection refused: localhost:8080";
            assertThat(SensitiveDataSanitizer.sanitize(msg)).isEqualTo(msg);
        }

        @Test
        @DisplayName("여러 민감 파라미터 — 모두 마스킹")
        void multipleKeys_allMasked() {
            String msg = "url?authkey=KEY1&api_key=KEY2&other=val";

            String result = SensitiveDataSanitizer.sanitize(msg);

            assertThat(result).contains("authkey=***");
            assertThat(result).contains("api_key=***");
            assertThat(result).doesNotContain("KEY1");
            assertThat(result).doesNotContain("KEY2");
        }
    }
}
