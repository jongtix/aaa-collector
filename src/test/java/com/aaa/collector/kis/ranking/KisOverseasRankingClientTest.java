package com.aaa.collector.kis.ranking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisOverseasRankingClient 단위 테스트")
class KisOverseasRankingClientTest {

    private static final String RANKING_PATH = "/uapi/overseas-stock/v1/ranking/trade-vol";

    private WireMockServer wireMockServer;
    private KisOverseasRankingClient client;

    @Mock private KisTokenService kisTokenService;
    @Mock private KisProperties kisProperties;

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

        KisAccountCredential credential =
                new KisAccountCredential("test", "12345678", "test-app-key", "test-app-secret");
        KisApiExecutor kisApiExecutor =
                new KisApiExecutor(restClient, kisProperties, kisTokenService);
        client = new KisOverseasRankingClient(kisApiExecutor);

        Mockito.lenient().when(kisProperties.accounts()).thenReturn(List.of(credential));
        Mockito.lenient().when(kisTokenService.getValidToken("test")).thenReturn("test-token");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private void stubRanking(String excd, String responseBody) {
        wireMockServer.stubFor(
                get(urlPathEqualTo(RANKING_PATH))
                        .withQueryParam("EXCD", equalTo(excd))
                        .withQueryParam("NDAY", equalTo("0"))
                        .withQueryParam("VOL_RANG", equalTo("0"))
                        .withHeader("tr_id", equalTo("HHDFS76310010"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(responseBody)));
    }

    private String rankingBody(String... symbols) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"조회되었습니다.\",\"output2\":[");
        for (int i = 0; i < symbols.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"symb\":\"")
                    .append(symbols[i])
                    .append("\",\"rank\":\"")
                    .append(i + 1)
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Nested
    @DisplayName("fetchRanking — NYS, NAS, AMS 각각 호출 후 머지")
    class FetchRanking {

        @Test
        @DisplayName("NYS + NAS + AMS 결과를 모두 합산하여 반환한다")
        void fetchRanking_threeExchanges_mergedResults() {
            stubRanking("NYS", rankingBody("AAPL", "MSFT"));
            stubRanking("NAS", rankingBody("NVDA", "TSLA"));
            stubRanking("AMS", rankingBody("SPY"));

            List<KisOverseasRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).hasSize(5);
            assertThat(result.stream().map(KisOverseasRankingResponse.RankedStock::symb).toList())
                    .containsExactlyInAnyOrder("AAPL", "MSFT", "NVDA", "TSLA", "SPY");
        }

        @Test
        @DisplayName("한 거래소 결과가 비어도 나머지 합산")
        void fetchRanking_oneExchangeEmpty_othersIncluded() {
            stubRanking("NYS", rankingBody("AAPL"));
            stubRanking(
                    "NAS",
                    "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"조회되었습니다.\",\"output2\":[]}");
            stubRanking("AMS", rankingBody("SPY"));

            List<KisOverseasRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).hasSize(2);
        }
    }
}
