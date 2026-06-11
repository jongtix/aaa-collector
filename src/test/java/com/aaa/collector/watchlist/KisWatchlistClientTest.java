package com.aaa.collector.watchlist;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
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
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class KisWatchlistClientTest {

    private static final String GROUP_LIST_PATH =
            "/uapi/domestic-stock/v1/quotations/intstock-grouplist";
    private static final String STOCK_LIST_PATH =
            "/uapi/domestic-stock/v1/quotations/intstock-stocklist-by-group";

    private WireMockServer wireMockServer;
    private KisWatchlistClient kisWatchlistClient;

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
        // no-op Sleeper: 실제 sleep 없이 테스트 (RetryExecutor 마이그레이션 이후)
        kisWatchlistClient = new KisWatchlistClient(kisApiExecutor, kisProperties, millis -> {});

        // 대부분의 테스트에서 공통으로 필요한 stubbing — lenient로 미사용 시 오류 방지
        Mockito.lenient().when(kisProperties.accounts()).thenReturn(List.of(credential));
        Mockito.lenient().when(kisProperties.userId()).thenReturn("testUser");
        Mockito.lenient().when(kisTokenService.getValidToken("test")).thenReturn("test-token");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Nested
    @DisplayName("fetchGroups")
    class FetchGroups {

        @Test
        @DisplayName("정상 응답 — 쿼리 파라미터 전송 확인, 그룹 목록 반환")
        void fetchGroups_normalResponse_sendsRequiredParamsAndReturnsGroups() {
            // Arrange
            wireMockServer.stubFor(
                    get(urlPathEqualTo(GROUP_LIST_PATH))
                            .withQueryParam("TYPE", equalTo("1"))
                            .withQueryParam("FID_ETC_CLS_CODE", equalTo("00"))
                            .withQueryParam("USER_ID", equalTo("testUser"))
                            .withHeader("Authorization", equalTo("Bearer test-token"))
                            .withHeader("appkey", equalTo("test-app-key"))
                            .withHeader("tr_id", equalTo("HHKCM113004C7"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "0",
                                                        "msg_cd": "MCA00000",
                                                        "msg1": "정상처리 되었습니다.",
                                                        "output2": [
                                                            {
                                                                "inter_grp_code": "001",
                                                                "inter_grp_name": "관심그룹1",
                                                                "ask_cnt": "3",
                                                                "data_rank": "1"
                                                            }
                                                        ]
                                                    }
                                                    """)));

            // Act
            List<KisGroupListResponse.Group> groups = kisWatchlistClient.fetchGroups();

            // Assert
            assertThat(groups).hasSize(1);
            assertThat(groups.getFirst().interGrpCode()).isEqualTo("001");
            assertThat(groups.getFirst().interGrpName()).isEqualTo("관심그룹1");
        }

        @Test
        @DisplayName("rt_cd != '0' 응답 — KisApiBusinessException 발생 (validateRtCd 동작 검증)")
        void fetchGroups_errorRtCd_throwsKisApiBusinessException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(GROUP_LIST_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "1",
                                                        "msg_cd": "EGW00123",
                                                        "msg1": "인증 오류",
                                                        "output2": null
                                                    }
                                                    """)));

            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(KisApiBusinessException.class)
                    .hasMessageContaining("rt_cd=1")
                    .hasMessageContaining("msg_cd=EGW00123");
        }

        @Test
        @DisplayName("output2 null 응답 — 빈 리스트 반환")
        void fetchGroups_nullOutput2_returnsEmptyList() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(GROUP_LIST_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "0",
                                                        "msg_cd": "MCA00000",
                                                        "msg1": "정상처리 되었습니다.",
                                                        "output2": null
                                                    }
                                                    """)));

            assertThat(kisWatchlistClient.fetchGroups()).isEmpty();
        }

        @Test
        @DisplayName("HTTP 4xx 응답 — RestClientException 발생")
        void fetchGroups_http4xx_throwsRestClientException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(GROUP_LIST_PATH)).willReturn(aResponse().withStatus(401)));

            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("HTTP 5xx 응답 — RestClientException 발생")
        void fetchGroups_http5xx_throwsRestClientException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(GROUP_LIST_PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(RestClientException.class);
        }
    }

    @Nested
    @DisplayName("fetchStocksByGroup")
    class FetchStocksByGroup {

        @Test
        @DisplayName("정상 응답 — 그룹코드 쿼리 파라미터 전송 확인, 종목 목록 반환")
        void fetchStocksByGroup_normalResponse_sendsGroupCodeAndReturnsStocks() {
            // Arrange
            wireMockServer.stubFor(
                    get(urlPathEqualTo(STOCK_LIST_PATH))
                            .withQueryParam("INTER_GRP_CODE", equalTo("001"))
                            .withQueryParam("USER_ID", equalTo("testUser"))
                            .withHeader("Authorization", equalTo("Bearer test-token"))
                            .withHeader("appkey", equalTo("test-app-key"))
                            .withHeader("tr_id", equalTo("HHKCM113004C6"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "0",
                                                        "msg_cd": "MCA00000",
                                                        "msg1": "정상처리 되었습니다.",
                                                        "output2": [
                                                            {
                                                                "fid_mrkt_cls_code": "J",
                                                                "jong_code": "005930",
                                                                "exch_code": "KRX",
                                                                "hts_kor_isnm": "삼성전자"
                                                            }
                                                        ]
                                                    }
                                                    """)));

            // Act
            List<KisStockListByGroupResponse.Stock> stocks =
                    kisWatchlistClient.fetchStocksByGroup("001");

            // Assert
            assertThat(stocks).hasSize(1);
            assertThat(stocks.getFirst().jongCode()).isEqualTo("005930");
            assertThat(stocks.getFirst().fidMrktClsCode()).isEqualTo("J");
            assertThat(stocks.getFirst().htsKorIsnm()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("rt_cd != '0' 응답 — KisApiBusinessException 발생 (validateRtCd 동작 검증)")
        void fetchStocksByGroup_errorRtCd_throwsKisApiBusinessException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(STOCK_LIST_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "1",
                                                        "msg_cd": "EGW00456",
                                                        "msg1": "그룹 조회 실패",
                                                        "output2": null
                                                    }
                                                    """)));

            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(KisApiBusinessException.class)
                    .hasMessageContaining("rt_cd=1")
                    .hasMessageContaining("msg_cd=EGW00456");
        }

        @Test
        @DisplayName("output2 null 응답 — 빈 리스트 반환")
        void fetchStocksByGroup_nullOutput2_returnsEmptyList() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(STOCK_LIST_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "0",
                                                        "msg_cd": "MCA00000",
                                                        "msg1": "정상처리 되었습니다.",
                                                        "output2": null
                                                    }
                                                    """)));

            assertThat(kisWatchlistClient.fetchStocksByGroup("001")).isEmpty();
        }

        @Test
        @DisplayName("HTTP 4xx 응답 — RestClientException 발생")
        void fetchStocksByGroup_http4xx_throwsRestClientException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(STOCK_LIST_PATH)).willReturn(aResponse().withStatus(401)));

            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(RestClientException.class);
        }

        @Test
        @DisplayName("HTTP 5xx 응답 — RestClientException 발생")
        void fetchStocksByGroup_http5xx_throwsRestClientException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(STOCK_LIST_PATH)).willReturn(aResponse().withStatus(500)));

            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(RestClientException.class);
        }
    }

    @Nested
    @DisplayName("계좌 설정")
    class AccountConfiguration {

        @Test
        @DisplayName("fetchGroups — 계좌 목록 비어있음 시 IllegalStateException 발생")
        void fetchGroups_noAccounts_throwsIllegalStateException() {
            when(kisProperties.accounts()).thenReturn(List.of());

            assertThatThrownBy(kisWatchlistClient::fetchGroups)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KIS 계좌 설정이 없습니다");
        }

        @Test
        @DisplayName("fetchStocksByGroup — 계좌 목록 비어있음 시 IllegalStateException 발생")
        void fetchStocksByGroup_noAccounts_throwsIllegalStateException() {
            when(kisProperties.accounts()).thenReturn(List.of());

            assertThatThrownBy(() -> kisWatchlistClient.fetchStocksByGroup("001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KIS 계좌 설정이 없습니다");
        }
    }
}
