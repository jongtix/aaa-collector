package com.aaa.collector.common.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.SmokeMockitoBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * 외부 API 클라이언트 설정 8개(빈 10개)의 HTTP 요청 팩토리 특성 테스트 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-013, AC-BOOT4-013).
 *
 * <p>Boot 4 전환에서 {@code ClientHttpRequestFactorySettings}가 {@code HttpClientSettings}로 바뀌어도 요청 팩토리
 * 종류, connect/read 타임아웃, 리다이렉트 처리가 그대로여야 한다. smoke 컨텍스트에 등록된 운영 {@link RestClient} 빈을 그대로 검사한다.
 *
 * <p>요청 팩토리와 타임아웃은 공개 접근자가 없어 Spring Framework 내부 필드({@code
 * DefaultRestClient.clientRequestFactory}, {@code
 * SimpleClientHttpRequestFactory.connectTimeout/readTimeout})를 읽는다 — 두 필드 이름은 spring-web
 * 6.2.19·7.0.10에서 같음을 확인했다. 리다이렉트는 WireMock으로 실제 동작을 본다.
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("외부 API RestClient 빈 — 요청 팩토리·타임아웃·리다이렉트 특성")
class ExternalClientTimeoutCharacterizationTest extends SmokeMockitoBase {

    private static WireMockServer wireMock;

    @Autowired private Map<String, RestClient> restClients;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(
                get(urlEqualTo("/redirect-src"))
                        .willReturn(
                                aResponse()
                                        .withStatus(302)
                                        .withHeader("Location", "/redirect-dst")));
        wireMock.stubFor(
                post(urlEqualTo("/redirect-src"))
                        .willReturn(
                                aResponse()
                                        .withStatus(302)
                                        .withHeader("Location", "/redirect-dst")));
        wireMock.stubFor(
                get(urlEqualTo("/redirect-dst"))
                        .willReturn(aResponse().withStatus(200).withBody("arrived")));
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    private RestClient bean(String beanName) {
        assertThat(restClients).containsKey(beanName);
        return restClients.get(beanName);
    }

    private RestClient pointedAtWireMock(String beanName) {
        return bean(beanName).mutate().baseUrl(wireMock.baseUrl()).build();
    }

    @Test
    @DisplayName("외부 API RestClient 빈은 정확히 10개다")
    void restClientBeanSet() {
        assertThat(restClients.keySet())
                .containsExactlyInAnyOrder(
                        "kisRestClient",
                        "dartRestClient",
                        "ecosRestClient",
                        "macroFredRestClient",
                        "cboeRestClient",
                        "yahooRestClient",
                        "koreaeximRestClient",
                        "finraRestClient",
                        "finraCdnRestClient",
                        "yahooExtendedHoursRestClient");
    }

    @ParameterizedTest(name = "{0} — connect {1}ms / read {2}ms")
    @CsvSource({
        "kisRestClient, 5000, 10000",
        "dartRestClient, 10000, 60000",
        "ecosRestClient, 5000, 30000",
        "macroFredRestClient, 5000, 30000",
        "cboeRestClient, 10000, 60000",
        "yahooRestClient, 10000, 60000",
        "koreaeximRestClient, 10000, 60000",
        "finraRestClient, 5000, 30000",
        "finraCdnRestClient, 5000, 30000",
        "yahooExtendedHoursRestClient, 5000, 30000"
    })
    @DisplayName("요청 팩토리는 JDK HttpURLConnection 기반이고 타임아웃이 설정값과 같다")
    void requestFactoryAndTimeouts(String beanName, int connectMillis, int readMillis) {
        // Arrange
        RestClient restClient = bean(beanName);

        // Act
        ClientHttpRequestFactory factory =
                (ClientHttpRequestFactory)
                        ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        // Assert
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout"))
                .isEqualTo(connectMillis);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(readMillis);
    }

    @Nested
    @DisplayName("리다이렉트 처리")
    class Redirects {

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "kisRestClient",
                    "dartRestClient",
                    "ecosRestClient",
                    "macroFredRestClient",
                    "cboeRestClient",
                    "yahooRestClient",
                    "koreaeximRestClient",
                    "finraRestClient",
                    "finraCdnRestClient",
                    "yahooExtendedHoursRestClient"
                })
        @DisplayName("GET 302는 따라가 최종 응답 본문을 받는다")
        void getFollowsRedirect(String beanName) {
            String body =
                    pointedAtWireMock(beanName)
                            .get()
                            .uri("/redirect-src")
                            .retrieve()
                            .body(String.class);

            assertThat(body).isEqualTo("arrived");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"kisRestClient", "finraRestClient"})
        @DisplayName("POST 302는 따라가지 않고 302 응답을 그대로 돌려준다")
        void postDoesNotFollowRedirect(String beanName) {
            ResponseEntity<String> response =
                    pointedAtWireMock(beanName)
                            .post()
                            .uri("/redirect-src")
                            .retrieve()
                            .toEntity(String.class);

            assertThat(response.getStatusCode().value()).isEqualTo(302);
        }
    }
}
