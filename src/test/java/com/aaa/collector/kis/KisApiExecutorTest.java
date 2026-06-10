package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
class KisApiExecutorTest {

    private static final KisAccountCredential ISA_CREDENTIAL =
            new KisAccountCredential("isa", "12345678", "appkey-isa", "appsecret-isa");
    private static final KisAccountCredential GOLD_CREDENTIAL =
            new KisAccountCredential("gold", "87654321", "appkey-gold", "appsecret-gold");
    private static final KisProperties.RateLimit RATE_LIMIT =
            new KisProperties.RateLimit(3, 15, 10);

    @Mock private KisTokenService kisTokenService;

    private MockRestServiceServer mockServer;
    private KisApiExecutor executor;

    @BeforeEach
    void setUp() {
        KisProperties kisProperties =
                new KisProperties(
                        "https://openapi.koreainvestment.com:9443",
                        "user",
                        List.of(ISA_CREDENTIAL, GOLD_CREDENTIAL),
                        RATE_LIMIT);

        RestClient.Builder serverBuilder = RestClient.builder().baseUrl(kisProperties.baseUrl());
        mockServer = MockRestServiceServer.bindTo(serverBuilder).build();
        RestClient serverRestClient = serverBuilder.build();

        executor = new KisApiExecutor(serverRestClient, kisProperties, kisTokenService);
    }

    /**
     * Minimal KisApiResponse stub that uses explicit @JsonProperty for snake_case deserialization.
     */
    static class StubResponse implements KisApiResponse {
        @JsonProperty("rt_cd")
        private final String rtCdValue;

        @JsonProperty("msg_cd")
        private final String msgCdValue;

        @JsonProperty("msg1")
        private final String msg1Value;

        StubResponse() {
            this.rtCdValue = "0";
            this.msgCdValue = "00000";
            this.msg1Value = "OK";
        }

        @Override
        public String rtCd() {
            return rtCdValue;
        }

        @Override
        public String msgCd() {
            return msgCdValue;
        }

        @Override
        public String msg1() {
            return msg1Value;
        }
    }

    @Nested
    @DisplayName("단일키 경로 — executeGet(uriCustomizer, trId, responseType)")
    class SingleKeyPath {

        @Test
        @DisplayName("단일키 경로 — firstCredential(isa) 사용, token 1회 획득 (AC-1 S1-2)")
        void executeGet_singleKey_usesFirstCredentialAlias() {
            // Arrange
            when(kisTokenService.getValidToken("isa")).thenReturn("test-token");
            mockServer
                    .expect(
                            MockRestRequestMatchers.requestTo(
                                    "https://openapi.koreainvestment.com:9443/test"))
                    .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                    .andExpect(MockRestRequestMatchers.header("appkey", "appkey-isa"))
                    .andRespond(
                            MockRestResponseCreators.withSuccess(
                                    "{\"rt_cd\":\"0\",\"msg1\":\"OK\"}",
                                    MediaType.APPLICATION_JSON));

            // Act
            StubResponse response =
                    executor.executeGet(
                            b -> b.path("/test").build(), "FHKST00000000", StubResponse.class);

            // Assert
            assertThat(response).isNotNull();
            verify(kisTokenService, times(1)).getValidToken("isa");
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("멀티키 경로 — executeGet(credential, uriCustomizer, trId, responseType)")
    class MultiKeyPath {

        @Test
        @DisplayName("멀티키 경로 — 지정된 credential의 alias로 토큰 획득 (AC-1 S1-1 일부)")
        void executeGet_multikey_usesGivenCredentialAlias() {
            // Arrange
            when(kisTokenService.getValidToken("gold")).thenReturn("gold-token");
            mockServer
                    .expect(
                            MockRestRequestMatchers.requestTo(
                                    "https://openapi.koreainvestment.com:9443/test"))
                    .andExpect(MockRestRequestMatchers.header("appkey", "appkey-gold"))
                    .andRespond(
                            MockRestResponseCreators.withSuccess(
                                    "{\"rt_cd\":\"0\",\"msg1\":\"OK\"}",
                                    MediaType.APPLICATION_JSON));

            // Act
            StubResponse response =
                    executor.executeGet(
                            GOLD_CREDENTIAL,
                            b -> b.path("/test").build(),
                            "FHKST00000000",
                            StubResponse.class);

            // Assert
            assertThat(response).isNotNull();
            verify(kisTokenService).getValidToken("gold");
            mockServer.verify();
        }

        @Test
        @DisplayName("HTTP 500 + msg_cd=EGW00201 — KisRateLimitException 던짐 (AC-3 S3-1, T-004)")
        void executeGet_multikey_http500WithEgw00201_throwsKisRateLimitException() {
            // Arrange
            when(kisTokenService.getValidToken("gold")).thenReturn("gold-token");
            mockServer
                    .expect(
                            MockRestRequestMatchers.requestTo(
                                    "https://openapi.koreainvestment.com:9443/test"))
                    .andRespond(
                            MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body(
                                            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"Rate limit\"}",
                                            StandardCharsets.UTF_8)
                                    .contentType(MediaType.APPLICATION_JSON));

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.executeGet(
                                            GOLD_CREDENTIAL,
                                            b -> b.path("/test").build(),
                                            "FHKST00000000",
                                            StubResponse.class))
                    .isInstanceOf(KisRateLimitException.class)
                    .extracting("alias")
                    .isEqualTo("gold");
        }

        @Test
        @DisplayName(
                "HTTP 500 + msg_cd != EGW00201 — RestClientResponseException 전파 (AC-3 S3-3, T-004)")
        void executeGet_multikey_http500NonEgw00201_throwsRestClientResponseException() {
            // Arrange
            when(kisTokenService.getValidToken("gold")).thenReturn("gold-token");
            mockServer
                    .expect(
                            MockRestRequestMatchers.requestTo(
                                    "https://openapi.koreainvestment.com:9443/test"))
                    .andRespond(
                            MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body(
                                            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW99999\",\"msg1\":\"Other error\"}",
                                            StandardCharsets.UTF_8)
                                    .contentType(MediaType.APPLICATION_JSON));

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.executeGet(
                                            GOLD_CREDENTIAL,
                                            b -> b.path("/test").build(),
                                            "FHKST00000000",
                                            StubResponse.class))
                    .isNotInstanceOf(KisRateLimitException.class)
                    .isInstanceOf(RestClientResponseException.class);
        }
    }
}
