package com.aaa.collector.common.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.SmokeMockitoBase;
import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import com.aaa.collector.dart.external.DartProperties;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenClient;
import com.aaa.collector.macro.ecos.EcosStatisticSearchResponse;
import com.aaa.collector.macro.fred.FredObservationsResponse;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.usdkrw.KoreaeximExchangeRateClient;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.exthours.ExtendedHoursRow;
import com.aaa.collector.stock.exthours.Session;
import com.aaa.collector.stock.exthours.YahooExtendedHoursClient;
import com.aaa.collector.stock.shortsale.overseas.FinraRegShoDailyResponse;
import com.aaa.collector.stock.shortsale.overseas.FinraShortSaleClient;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnDailyFileClient;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnFetchResult;
import com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnShortSaleBackfillProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * 외부 API 클라이언트 설정 8개의 대표 응답 골든 — Spring 관리 메시지 변환 경유 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-010,
 * AC-BOOT4-010 (ii)).
 *
 * <p>매퍼를 직접 만들지 않는다. smoke 컨텍스트의 운영 {@link RestClient} 빈(자동구성 {@code RestClient.Builder}로 만든 것)을
 * {@code mutate()}로 base URL만 WireMock으로 바꿔 쓰므로, 빈의 메시지 컨버터(전역 {@code SNAKE_CASE}·JSR-310 등 Spring
 * 관리 매퍼 설정)·요청 팩토리·기본 헤더가 그대로 유지된다. WireMock이 Boot 3.5 시점 픽스처({@code contract/external/*})를 응답하고,
 * 운영 클라이언트 클래스가 읽어 낸 DTO 필드 값을 단언한다. ECOS·FRED는 클라이언트 역할을 서비스(DB 적재 포함)가 겸하므로, 서비스와 같은 읽기 체인({@code
 * get().uri().retrieve().body(운영 DTO)})을 운영 빈에 그대로 호출한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("외부 API 대표 응답 골든 — 운영 RestClient 빈의 Spring 관리 컨버터 경유")
class SpringManagedMapperClientContractTest extends SmokeMockitoBase {

    private static WireMockServer wireMock;

    @Autowired private Map<String, RestClient> restClients;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    private RestClient pointedAtWireMock(String beanName) {
        assertThat(restClients).containsKey(beanName);
        return restClients.get(beanName).mutate().baseUrl(wireMock.baseUrl()).build();
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("contract/external/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stubJson(String path, String fixtureName) {
        wireMock.stubFor(
                get(urlPathEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(fixture(fixtureName))));
    }

    @Nested
    @DisplayName("DartClientConfig — DartDisclosureClient")
    class DartDisclosure {

        @Test
        @DisplayName("list.json 응답을 DartListResponse로 읽는다")
        void readsDisclosureList() {
            // Arrange
            stubJson("/api/list.json", "dart-list.json");
            DartDisclosureClient client =
                    new DartDisclosureClient(
                            new DartProperties(), pointedAtWireMock("dartRestClient"));

            // Act
            DartListResponse response =
                    client.fetchPage(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 25), 1, null);

            // Assert
            assertThat(response)
                    .isEqualTo(
                            new DartListResponse(
                                    "000",
                                    "정상",
                                    3861,
                                    100,
                                    1,
                                    39,
                                    List.of(
                                            DartListResponse.DisclosureItem.of(
                                                    "00260453",
                                                    "E",
                                                    "",
                                                    "증권발행실적보고서(집합투자증권)(삼성아세안플러스베트남증권자투자신탁UH[주식])",
                                                    "20260625000025",
                                                    "삼성자산운용",
                                                    "20260625",
                                                    ""))));
        }
    }

    @Nested
    @DisplayName("KisConfig — KisTokenClient")
    class KisToken {

        @Test
        @DisplayName("토큰 요청 본문과 SNAKE_CASE 응답 매핑이 3.5와 같다")
        void requestsAndReadsToken() {
            // Arrange
            wireMock.stubFor(
                    post(urlEqualTo("/oauth2/tokenP"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(fixture("kis-token.json"))));
            KisTokenClient client = new KisTokenClient(pointedAtWireMock("kisRestClient"));

            // Act — 응답 레코드가 패키지 비공개라 타입을 이름으로 쓰지 않고 속성 이름으로 꺼낸다
            Object response =
                    client.requestToken(
                            new KisAccountCredential(
                                    "contract", "12345678", "contract-app-key", "contract-secret"));

            // Assert
            assertThat(response)
                    .extracting("accessToken", "tokenType", "expiresIn", "accessTokenTokenExpired")
                    .containsExactly(
                            "contract-test-token", "Bearer", 86_400, "2026-06-16 21:10:10");
            wireMock.verify(
                    postRequestedFor(urlEqualTo("/oauth2/tokenP"))
                            .withRequestBody(
                                    equalToJson(
                                            """
                                            {"grant_type":"client_credentials",\
                                            "appkey":"contract-app-key",\
                                            "appsecret":"contract-secret"}""",
                                            false,
                                            false)));
        }
    }

    @Nested
    @DisplayName("EcosClientConfig — StatisticSearch 읽기 체인")
    class EcosStatisticSearch {

        @Test
        @DisplayName("StatisticSearch 응답을 EcosStatisticSearchResponse로 읽는다")
        void readsStatisticSearch() {
            // Arrange
            String path =
                    "/api/StatisticSearch/key/json/kr/1/100/722Y001/D/20260521/20260620/0101000";
            stubJson(path, "ecos-statistic-search.json");

            // Act
            EcosStatisticSearchResponse response =
                    pointedAtWireMock("ecosRestClient")
                            .get()
                            .uri(path)
                            .retrieve()
                            .body(EcosStatisticSearchResponse.class);

            // Assert
            assertThat(response)
                    .isEqualTo(
                            new EcosStatisticSearchResponse(
                                    new EcosStatisticSearchResponse.StatisticSearch(
                                            29,
                                            List.of(
                                                    new EcosStatisticSearchResponse.Row(
                                                            "20260618", "2.5"))),
                                    null));
        }
    }

    @Nested
    @DisplayName("FredClientConfig — series/observations 읽기 체인")
    class FredObservations {

        @Test
        @DisplayName("observations 응답을 FredObservationsResponse로 읽는다")
        void readsObservations() {
            // Arrange
            String path = "/fred/series/observations";
            stubJson(path, "fred-observations.json");

            // Act
            FredObservationsResponse response =
                    pointedAtWireMock("macroFredRestClient")
                            .get()
                            .uri(path + "?series_id=VIXCLS&file_type=json")
                            .retrieve()
                            .body(FredObservationsResponse.class);

            // Assert
            assertThat(response)
                    .isEqualTo(
                            new FredObservationsResponse(
                                    9512,
                                    List.of(
                                            new FredObservationsResponse.Observation(
                                                    "2026-06-11", "19.44"),
                                            new FredObservationsResponse.Observation(
                                                    "2026-06-12", "."))));
        }
    }

    @Nested
    @DisplayName("MarketIndicatorClientConfig — KoreaeximExchangeRateClient")
    class Koreaexim {

        @Test
        @DisplayName("숫자 result를 포함한 환율 배열에서 USD 행만 읽는다")
        void readsUsdRate() {
            // Arrange
            stubJson("/site/program/financial/exchangeJSON", "koreaexim-exchange.json");
            KoreaeximExchangeRateClient client =
                    new KoreaeximExchangeRateClient(
                            pointedAtWireMock("koreaeximRestClient"), "contract-key", 0, 0L, 0, 0L);
            LocalDate date = LocalDate.of(2026, 6, 19);

            // Act
            List<MarketIndicatorRow> rows = client.fetchDaily(date);

            // Assert
            assertThat(rows)
                    .containsExactly(
                            new MarketIndicatorRow(
                                    IndicatorCode.USDKRW,
                                    date,
                                    null,
                                    null,
                                    null,
                                    new BigDecimal("1523.4000"),
                                    "KOREAEXIM"));
        }
    }

    @Nested
    @DisplayName("FinraClientConfig — FinraShortSaleClient")
    class Finra {

        @Test
        @DisplayName("regShoDaily 요청 본문과 응답 매핑(LocalDate·BigDecimal)이 3.5와 같다")
        void requestsAndReadsRegShoDaily() {
            // Arrange
            wireMock.stubFor(
                    post(urlEqualTo("/data/group/otcMarket/name/regShoDaily"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withHeader("record-total", "3")
                                            .withBody(fixture("finra-regshodaily.json"))));
            FinraShortSaleClient client =
                    new FinraShortSaleClient(pointedAtWireMock("finraRestClient"));
            LocalDate date = LocalDate.of(2026, 1, 6);

            // Act
            List<FinraRegShoDailyResponse> rows = client.fetchRegShoDaily(date);

            // Assert
            assertThat(rows)
                    .usingRecursiveFieldByFieldElementComparator()
                    .containsExactly(
                            new FinraRegShoDailyResponse(
                                    date,
                                    "AAPL",
                                    new BigDecimal("69397"),
                                    new BigDecimal("154130")),
                            new FinraRegShoDailyResponse(
                                    date,
                                    "AAPL",
                                    new BigDecimal("4891733"),
                                    new BigDecimal("17699980")),
                            new FinraRegShoDailyResponse(
                                    date,
                                    "AAPL",
                                    new BigDecimal("198716.5"),
                                    new BigDecimal("588753")));
            wireMock.verify(
                    postRequestedFor(urlEqualTo("/data/group/otcMarket/name/regShoDaily"))
                            .withRequestBody(
                                    equalToJson(
                                            """
                                            {"compareFilters":[{"compareType":"EQUAL",\
                                            "fieldName":"tradeReportDate","fieldValue":"2026-01-06"}],\
                                            "limit":5000,"offset":0}""",
                                            false,
                                            false)));
        }
    }

    @Nested
    @DisplayName("FinraCdnBackfillConfig — FinraCdnDailyFileClient")
    class FinraCdn {

        @Test
        @DisplayName("CNMS 텍스트 파일 본문을 그대로 읽는다")
        void readsCnmsFile() {
            // Arrange
            wireMock.stubFor(
                    get(urlEqualTo("/equity/regsho/daily/CNMSshvol20260619.txt"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "text/plain")
                                            .withBody(fixture("finra-cdn-shvol.txt"))));
            FinraCdnDailyFileClient client =
                    new FinraCdnDailyFileClient(
                            pointedAtWireMock("finraCdnRestClient"),
                            new FinraCdnShortSaleBackfillProperties());

            // Act
            FinraCdnFetchResult result = client.fetch(LocalDate.of(2026, 6, 19));

            // Assert
            assertThat(result)
                    .isEqualTo(
                            new FinraCdnFetchResult.Found(List.of(fixture("finra-cdn-shvol.txt"))));
        }
    }

    @Nested
    @DisplayName("ExtendedHoursClientConfig — YahooExtendedHoursClient")
    class YahooExtendedHours {

        @Test
        @DisplayName("chart 응답(Map 역직렬화)에서 PRE 세션 가격을 읽고 기본 User-Agent를 보낸다")
        void readsPreSessionPrice() {
            // Arrange
            stubJson("/v8/finance/chart/AAPL", "yahoo-extended-hours-chart.json");
            YahooExtendedHoursClient client =
                    new YahooExtendedHoursClient(pointedAtWireMock("yahooExtendedHoursRestClient"));
            Stock aapl =
                    Stock.builder()
                            .symbol("AAPL")
                            .nameKo("애플")
                            .market(Market.NASDAQ)
                            .assetType(AssetType.STOCK)
                            .listedDate(LocalDate.of(1980, 12, 12))
                            .build();

            // Act
            Optional<ExtendedHoursRow> row = client.fetch(aapl, Session.PRE);

            // Assert
            assertThat(row)
                    .contains(
                            new ExtendedHoursRow(
                                    null,
                                    Session.PRE,
                                    LocalDate.of(2025, 6, 24),
                                    new BigDecimal("295.2500"),
                                    new BigDecimal("295.9500"),
                                    "YAHOO"));
            wireMock.verify(
                    getRequestedFor(urlPathEqualTo("/v8/finance/chart/AAPL"))
                            .withHeader(
                                    "User-Agent",
                                    equalTo(
                                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
                                                    + " AppleWebKit/537.36")));
        }
    }
}
