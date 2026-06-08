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
@DisplayName("KisDomesticRankingClient 단위 테스트")
class KisDomesticRankingClientTest {

    private static final String RANKING_PATH = "/uapi/domestic-stock/v1/quotations/volume-rank";

    private WireMockServer wireMockServer;
    private KisDomesticRankingClient client;

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
        client = new KisDomesticRankingClient(kisApiExecutor);

        Mockito.lenient().when(kisProperties.accounts()).thenReturn(List.of(credential));
        Mockito.lenient().when(kisTokenService.getValidToken("test")).thenReturn("test-token");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private void stubRanking(String responseBody) {
        wireMockServer.stubFor(
                get(urlPathEqualTo(RANKING_PATH))
                        .withQueryParam("FID_BLNG_CLS_CODE", equalTo("3"))
                        .withQueryParam("FID_COND_MRKT_DIV_CODE", equalTo("J"))
                        .withQueryParam("FID_COND_SCR_DIV_CODE", equalTo("20171"))
                        .withQueryParam("FID_INPUT_ISCD", equalTo("0000"))
                        .withHeader("tr_id", equalTo("FHPST01710000"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(responseBody)));
    }

    @Nested
    @DisplayName("fetchRanking — 국내 거래금액 순위 조회")
    class FetchRanking {

        @Test
        @DisplayName("정상 응답 — output 목록 반환")
        void fetchRanking_normalResponse_returnsOutput() {
            String body =
                    """
                    {
                        "rt_cd": "0",
                        "msg_cd": "MCA00000",
                        "msg1": "조회되었습니다.",
                        "output": [
                            {"mksc_shrn_iscd": "005930", "data_rank": "1", "hts_kor_isnm": "삼성전자"},
                            {"mksc_shrn_iscd": "000660", "data_rank": "2", "hts_kor_isnm": "SK하이닉스"}
                        ]
                    }
                    """;
            stubRanking(body);

            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).hasSize(2);
            assertThat(result.getFirst().mkscShrnIscd()).isEqualTo("005930");
            assertThat(result.get(1).mkscShrnIscd()).isEqualTo("000660");
        }

        @Test
        @DisplayName("빈 output 배열 — 빈 목록 반환")
        void fetchRanking_emptyOutput_returnsEmptyList() {
            String body =
                    """
                    {
                        "rt_cd": "0",
                        "msg_cd": "MCA00000",
                        "msg1": "조회되었습니다.",
                        "output": []
                    }
                    """;
            stubRanking(body);

            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("dataRank 순서 확인 — 1위 종목이 첫 번째")
        void fetchRanking_ranksAreOrdered() {
            String body =
                    """
                    {
                        "rt_cd": "0",
                        "msg_cd": "MCA00000",
                        "msg1": "조회되었습니다.",
                        "output": [
                            {"mksc_shrn_iscd": "005930", "data_rank": "1", "hts_kor_isnm": "삼성전자"},
                            {"mksc_shrn_iscd": "035420", "data_rank": "2", "hts_kor_isnm": "NAVER"}
                        ]
                    }
                    """;
            stubRanking(body);

            List<KisDomesticRankingResponse.RankedStock> result = client.fetchRanking();

            assertThat(result.getFirst().dataRank()).isEqualTo("1");
        }
    }
}
