package com.aaa.collector.watchlist;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimiterRegistry;
import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
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
import org.springframework.web.util.UriBuilder;

@ExtendWith(MockitoExtension.class)
class KisStockInfoClientTest {

    private static final String DOMESTIC_PATH =
            "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private static final String OVERSEAS_PATH = "/uapi/overseas-price/v1/quotations/search-info";

    @Mock private KisTokenService kisTokenService;
    @Mock private KisProperties kisProperties;

    private WireMockServer wireMockServer;
    private KisStockInfoClient kisStockInfoClient;
    private KisAccountCredential credential;
    private BatchRestExecutor batchRestExecutor;

    /** 멀티키 경로로 BatchRestExecutor를 경유하여 fetch하는 fetcher (서비스가 ②단계에서 백킹하는 형태와 동일). */
    private KisStockInfoClient.StockInfoFetcher fetcherFor(
            BatchRestExecutor batchRestExecutor, String symbol) {
        return new KisStockInfoClient.StockInfoFetcher() {
            @Override
            public <T extends KisApiResponse> BatchResult<T> fetch(
                    Function<UriBuilder, URI> uriCustomizer, String trId, Class<T> responseType) {
                return batchRestExecutor.execute(
                        credential, uriCustomizer, trId, responseType, symbol);
            }
        };
    }

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

        credential =
                new KisAccountCredential("test", "12345678", "test-app-key", "test-app-secret");

        KisApiExecutor kisApiExecutor =
                new KisApiExecutor(restClient, kisProperties, kisTokenService);
        kisStockInfoClient = new KisStockInfoClient(new StockInfoParser());

        KisProperties realProperties =
                new KisProperties(
                        "https://localhost",
                        "testUser",
                        List.of(credential),
                        new KisProperties.RateLimit(100, 100, 50));
        KisRateLimiterRegistry registry = new KisRateLimiterRegistry(realProperties);
        Sleeper noopSleeper = millis -> {};
        batchRestExecutor = new BatchRestExecutor(kisApiExecutor, registry, noopSleeper);

        Mockito.lenient().when(kisProperties.accounts()).thenReturn(List.of(credential));
        Mockito.lenient().when(kisTokenService.getValidToken("test")).thenReturn("test-token");
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private StockInfo fetch(String symbol, Market market) {
        return kisStockInfoClient.fetchStockInfo(
                symbol, market, fetcherFor(batchRestExecutor, symbol));
    }

    /**
     * Stubs domestic API with mket_id_cd for authoritative market determination (REQ-STOCKMETA-001,
     * SPEC-COLLECTOR-STOCKMETA-001).
     */
    private void stubDomestic(
            String symbol,
            String sctyGrpIdCd,
            String sctsDt,
            String kosdaqDt,
            String nameEn,
            String mketIdCd) {
        String body =
                "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"조회되었습니다.\","
                        + "\"output\":{"
                        + "\"scty_grp_id_cd\":\""
                        + sctyGrpIdCd
                        + "\","
                        + "\"prdt_eng_name\":\""
                        + nameEn
                        + "\","
                        + "\"scts_mket_lstg_dt\":\""
                        + sctsDt
                        + "\","
                        + "\"kosdaq_mket_lstg_dt\":\""
                        + kosdaqDt
                        + "\","
                        + "\"mket_id_cd\":\""
                        + mketIdCd
                        + "\""
                        + "}}";
        wireMockServer.stubFor(
                get(urlPathEqualTo(DOMESTIC_PATH))
                        .withQueryParam("PRDT_TYPE_CD", equalTo("300"))
                        .withQueryParam("PDNO", equalTo(symbol))
                        .withHeader("tr_id", equalTo("CTPF1002R"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    /** Backwards-compat overload — stubs with STK (KOSPI) as default mket_id_cd. */
    private void stubDomestic(
            String symbol, String sctyGrpIdCd, String sctsDt, String kosdaqDt, String nameEn) {
        // Default to STK(KOSPI) for existing tests that originally used Market.KOSPI directly.
        // Tests for KOSDAQ should use the explicit overload.
        stubDomestic(symbol, sctyGrpIdCd, sctsDt, kosdaqDt, nameEn, "STK");
    }

    private void stubOverseas(
            String symbol,
            String prdtTypeCd,
            String dvsnCd,
            String riskCd,
            String nameEn,
            String lstgDt) {
        String body =
                "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"조회되었습니다.\","
                        + "\"output\":{"
                        + "\"ovrs_stck_dvsn_cd\":\""
                        + dvsnCd
                        + "\","
                        + "\"ovrs_stck_etf_risk_drtp_cd\":\""
                        + riskCd
                        + "\","
                        + "\"prdt_eng_name\":\""
                        + nameEn
                        + "\","
                        + "\"lstg_dt\":\""
                        + lstgDt
                        + "\""
                        + "}}";
        wireMockServer.stubFor(
                get(urlPathEqualTo(OVERSEAS_PATH))
                        .withQueryParam("PRDT_TYPE_CD", equalTo(prdtTypeCd))
                        .withQueryParam("PDNO", equalTo(symbol))
                        .withHeader("tr_id", equalTo("CTPF1702R"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    @Nested
    @DisplayName("fetchStockInfo — 국내")
    class Domestic {

        @Test
        @DisplayName("KOSPI 일반주식 (ST) → STOCK, scts_mket_lstg_dt 사용")
        void fetchStockInfo_kospiStock_returnsStock() {
            stubDomestic("005930", "ST", "20750101", "", "Samsung Electronics");

            StockInfo info = fetch("005930", Market.KOSPI);

            assertThat(info.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(info.nameEn()).isEqualTo("Samsung Electronics");
            assertThat(info.listedDate()).isEqualTo(LocalDate.of(2075, 1, 1));
        }

        @Test
        @DisplayName("KOSPI ETF (EF) → ETF")
        void fetchStockInfo_kospiEtf_returnsEtf() {
            stubDomestic("069500", "EF", "20020414", "", "KODEX 200 ETF");

            assertThat(fetch("069500", Market.KOSPI).assetType()).isEqualTo(AssetType.ETF);
        }

        @Test
        @DisplayName("KOSPI ETF (FE) → ETF")
        void fetchStockInfo_kospiEtfFe_returnsEtf() {
            stubDomestic("069660", "FE", "20030101", "", "KODEX ETF");

            assertThat(fetch("069660", Market.KOSPI).assetType()).isEqualTo(AssetType.ETF);
        }

        @Test
        @DisplayName("KOSPI ETN (EN) → ETN")
        void fetchStockInfo_kospiEtn_returnsEtn() {
            stubDomestic("Q530067", "EN", "20200101", "", "Samsung Gold ETN");

            assertThat(fetch("Q530067", Market.KOSPI).assetType()).isEqualTo(AssetType.ETN);
        }

        @Test
        @DisplayName("KOSDAQ 종목 — kosdaq_mket_lstg_dt 사용 (mket_id_cd=KSQ)")
        void fetchStockInfo_kosdaqStock_usesKosdaqDate() {
            stubDomestic("247540", "ST", "", "20190628", "EcoPro BM", "KSQ");

            StockInfo info = fetch("247540", Market.KOSPI); // coarse routing market; KSQ→KOSDAQ

            assertThat(info.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(info.nameEn()).isEqualTo("EcoPro BM");
            assertThat(info.market()).isEqualTo(Market.KOSDAQ); // authoritative from mket_id_cd
            assertThat(info.listedDate()).isEqualTo(LocalDate.of(2019, 6, 28));
        }

        @Test
        @DisplayName("상장일자 빈 값 — listedDate null")
        void fetchStockInfo_emptyListedDate_returnsNullDate() {
            stubDomestic("005930", "ST", "", "", "Samsung");

            assertThat(fetch("005930", Market.KOSPI).listedDate()).isNull();
        }

        @Test
        @DisplayName("API 오류 응답 (rt_cd != 0) — KisApiBusinessException 발생")
        void fetchStockInfo_errorResponse_throwsKisApiBusinessException() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(DOMESTIC_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                                    {
                                                        "rt_cd": "1",
                                                        "msg_cd": "EGW00201",
                                                        "msg1": "조회 오류"
                                                    }
                                                    """)));

            assertThatThrownBy(() -> fetch("005930", Market.KOSPI))
                    .isInstanceOf(KisApiBusinessException.class)
                    .hasMessageContaining("rt_cd=1")
                    .hasMessageContaining("msg_cd=EGW00201");
        }

        @Test
        @DisplayName("지원하지 않는 시장 (KRX) — IllegalArgumentException 발생")
        void fetchStockInfo_unsupportedMarket_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> fetch("KRX001", Market.KRX))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("EGW00201 재시도 소진으로 BatchResult.skip — null 반환 (graceful skip, AC-9)")
        void fetchStockInfo_batchResultSkip_returnsNull() {
            wireMockServer.stubFor(
                    get(urlPathEqualTo(DOMESTIC_PATH))
                            .willReturn(
                                    aResponse()
                                            .withStatus(500)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody("{\"msg_cd\":\"EGW00201\"}")));

            StockInfo info = fetch("005930", Market.KOSPI);

            assertThat(info).isNull();
        }
    }

    @Nested
    @DisplayName("fetchStockInfo — 해외")
    class Overseas {

        @Test
        @DisplayName("NASDAQ 일반주식 (dvsn=01) → STOCK, PRDT_TYPE_CD=512")
        void fetchStockInfo_nasdaqStock_returnsStock() {
            stubOverseas("AAPL", "512", "01", "", "Apple Inc", "19801212");

            StockInfo info = fetch("AAPL", Market.NASDAQ);

            assertThat(info.assetType()).isEqualTo(AssetType.STOCK);
            assertThat(info.nameEn()).isEqualTo("Apple Inc");
            assertThat(info.listedDate()).isEqualTo(LocalDate.of(1980, 12, 12));
        }

        @Test
        @DisplayName("NYSE ETF (dvsn=03, riskCd=001) → ETF, PRDT_TYPE_CD=513")
        void fetchStockInfo_nyseEtf_returnsEtf() {
            stubOverseas("SPY", "513", "03", "001", "SPDR S&P 500 ETF", "19930122");

            assertThat(fetch("SPY", Market.NYSE).assetType()).isEqualTo(AssetType.ETF);
        }

        @Test
        @DisplayName("AMEX ETN (dvsn=03, riskCd=002) → ETN, PRDT_TYPE_CD=529")
        void fetchStockInfo_amexEtn_returnsEtn() {
            stubOverseas("ARKK", "529", "03", "002", "ARK Innovation ETN", "20141031");

            assertThat(fetch("ARKK", Market.AMEX).assetType()).isEqualTo(AssetType.ETN);
        }

        @Test
        @DisplayName("해외 VIX ETN (dvsn=03, riskCd=006) → ETN")
        void fetchStockInfo_vixEtn_returnsEtn() {
            stubOverseas("UVXY", "512", "03", "006", "VIX ETN", "20110101");

            assertThat(fetch("UVXY", Market.NASDAQ).assetType()).isEqualTo(AssetType.ETN);
        }
    }

    @Nested
    @DisplayName("parseDate — KIS 특수값 처리")
    class ParseDate {

        @Test
        @DisplayName("00000000 — null 반환, 예외 없음")
        void fetchStockInfo_lstgDtAllZeros_returnsNullDate() {
            stubOverseas("AAPL", "512", "01", "", "Apple Inc", "00000000");

            StockInfo info = fetch("AAPL", Market.NASDAQ);

            assertThat(info.listedDate()).isNull();
        }

        @Test
        @DisplayName("null — null 반환")
        void fetchStockInfo_lstgDtNull_returnsNullDate() {
            stubOverseas("AAPL", "512", "01", "", "Apple Inc", "");

            StockInfo info = fetch("AAPL", Market.NASDAQ);

            assertThat(info.listedDate()).isNull();
        }

        @Test
        @DisplayName("유효 날짜 20240115 — LocalDate(2024,1,15) 반환")
        void fetchStockInfo_lstgDtValid_returnsLocalDate() {
            stubOverseas("AAPL", "512", "01", "", "Apple Inc", "20240115");

            StockInfo info = fetch("AAPL", Market.NASDAQ);

            assertThat(info.listedDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        }
    }
}
