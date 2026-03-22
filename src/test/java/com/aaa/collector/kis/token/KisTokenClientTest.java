package com.aaa.collector.kis.token;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class KisTokenClientTest {

    private WireMockServer wireMockServer;
    private KisTokenClient kisTokenClient;
    private KisAccountCredential credential;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        ObjectMapper objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        RestClient restClient =
                RestClient.builder()
                        .baseUrl(wireMockServer.baseUrl())
                        .messageConverters(
                                converters -> {
                                    converters.clear();
                                    converters.add(
                                            new MappingJackson2HttpMessageConverter(objectMapper));
                                })
                        .build();
        kisTokenClient = new KisTokenClient(restClient);
        credential =
                new KisAccountCredential("test", "12345678", "test-app-key", "test-app-secret");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("정상 응답 시 KisTokenResponse 필드가 올바르게 매핑된다")
    void requestToken_withValidResponse_mapsAllFields() {
        wireMockServer.stubFor(
                post(urlEqualTo("/oauth2/tokenP"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                    "access_token": "test-token-123",
                                                    "token_type": "Bearer",
                                                    "expires_in": 86400,
                                                    "access_token_token_expired": "2026-03-17 08:30:00"
                                                }
                                                """)));

        KisTokenResponse response = kisTokenClient.requestToken(credential);

        assertThat(response.accessToken()).isEqualTo("test-token-123");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(86400);
        assertThat(response.accessTokenTokenExpired()).isEqualTo("2026-03-17 08:30:00");

        wireMockServer.verify(
                postRequestedFor(urlEqualTo("/oauth2/tokenP"))
                        .withHeader("Content-Type", containing("application/json"))
                        .withRequestBody(
                                matchingJsonPath("$.grant_type", equalTo("client_credentials")))
                        .withRequestBody(matchingJsonPath("$.appkey", equalTo("test-app-key")))
                        .withRequestBody(
                                matchingJsonPath("$.appsecret", equalTo("test-app-secret"))));
    }

    @Test
    @DisplayName("HTTP 5xx 응답 시 RestClientException이 발생한다")
    void requestToken_with5xxResponse_throwsRestClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/oauth2/tokenP"))
                        .willReturn(
                                aResponse()
                                        .withStatus(500)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                    "error": "Internal Server Error"
                                                }
                                                """)));

        assertThatThrownBy(() -> kisTokenClient.requestToken(credential))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("HTTP 4xx 응답 시 RestClientException이 발생한다")
    void requestToken_with4xxResponse_throwsRestClientException() {
        wireMockServer.stubFor(
                post(urlEqualTo("/oauth2/tokenP"))
                        .willReturn(
                                aResponse()
                                        .withStatus(401)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                                {
                                                    "error": "Unauthorized"
                                                }
                                                """)));

        assertThatThrownBy(() -> kisTokenClient.requestToken(credential))
                .isInstanceOf(RestClientException.class);
    }
}
