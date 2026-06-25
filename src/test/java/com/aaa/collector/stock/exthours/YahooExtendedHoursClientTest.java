package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * YahooExtendedHoursClient 단위 테스트 (SPEC-COLLECTOR-EXTHOURS-001 T4).
 *
 * <p>MockRestServiceServer를 사용하여 HTTP 호출 없이 파싱 로직을 검증한다.
 */
@DisplayName("YahooExtendedHoursClient 단위 테스트")
class YahooExtendedHoursClientTest {

    // 2026-06-24 세션 경계 (ET = UTC-4, EDT)
    // PRE: 04:00 ET = 08:00 UTC = epoch 1750752000, end 09:30 ET = epoch 1750757400
    // REG: 09:30 ET = epoch 1750757400, end 16:00 ET = epoch 1750780800
    // POST: 16:00 ET = epoch 1750780800, end 20:00 ET = epoch 1750795200

    /** PRE 세션 분봉 3개 포함 응답. 마지막 non-null close = 295.25 */
    private static final String PRE_SESSION_JSON =
            """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "symbol": "AAPL",
                    "hasPrePostMarketData": true,
                    "chartPreviousClose": 295.95,
                    "regularMarketPrice": 293.08,
                    "currentTradingPeriod": {
                      "pre":     { "start": 1750752000, "end": 1750757400 },
                      "regular": { "start": 1750757400, "end": 1750780800 },
                      "post":    { "start": 1750780800, "end": 1750795200 }
                    }
                  },
                  "timestamp": [1750752060, 1750752120, 1750752180],
                  "indicators": {
                    "quote": [{
                      "open":  [294.0, 294.5, 295.0],
                      "high":  [294.5, 295.0, 295.5],
                      "low":   [293.5, 294.0, 294.5],
                      "close": [294.1, 294.8, 295.25],
                      "volume":[0, 0, 0]
                    }]
                  }
                }]
              }
            }
            """;

    /** AFTER 세션 분봉 3개 포함 응답. 마지막 non-null close = 290.8025 */
    private static final String AFTER_SESSION_JSON =
            """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "symbol": "AAPL",
                    "hasPrePostMarketData": true,
                    "chartPreviousClose": 295.95,
                    "regularMarketPrice": 293.08,
                    "currentTradingPeriod": {
                      "pre":     { "start": 1750752000, "end": 1750757400 },
                      "regular": { "start": 1750757400, "end": 1750780800 },
                      "post":    { "start": 1750780800, "end": 1750795200 }
                    }
                  },
                  "timestamp": [1750780860, 1750780920, 1750780980],
                  "indicators": {
                    "quote": [{
                      "open":  [293.0, 292.5, 291.0],
                      "high":  [293.5, 293.0, 291.5],
                      "low":   [292.0, 292.0, 290.5],
                      "close": [292.5, 291.3, 290.8025],
                      "volume":[0, 0, 0]
                    }]
                  }
                }]
              }
            }
            """;

    /** hasPrePostMarketData=false 응답 */
    private static final String NO_PRE_POST_JSON =
            """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "symbol": "AAPL",
                    "hasPrePostMarketData": false,
                    "chartPreviousClose": 295.95,
                    "regularMarketPrice": 293.08,
                    "currentTradingPeriod": {
                      "pre":     { "start": 1750752000, "end": 1750757400 },
                      "regular": { "start": 1750757400, "end": 1750780800 },
                      "post":    { "start": 1750780800, "end": 1750795200 }
                    }
                  },
                  "timestamp": [1750757400],
                  "indicators": {
                    "quote": [{ "close": [293.08], "volume": [1000] }]
                  }
                }]
              }
            }
            """;

    /**
     * PRE 세션 분봉 중 중간에 null close 포함 응답 (EC-1 — 역방향 마지막 non-null close 추출 검증).
     *
     * <p>분봉 순서: [null, 294.8, null]. 역방향 탐색 → 마지막 non-null = 294.8
     */
    private static final String PRE_MIDDLE_NULL_JSON =
            """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "symbol": "AAPL",
                    "hasPrePostMarketData": true,
                    "chartPreviousClose": 295.95,
                    "regularMarketPrice": 293.08,
                    "currentTradingPeriod": {
                      "pre":     { "start": 1750752000, "end": 1750757400 },
                      "regular": { "start": 1750757400, "end": 1750780800 },
                      "post":    { "start": 1750780800, "end": 1750795200 }
                    }
                  },
                  "timestamp": [1750752060, 1750752120, 1750752180],
                  "indicators": {
                    "quote": [{
                      "open":  [294.0, 294.5, null],
                      "high":  [294.5, 295.0, null],
                      "low":   [293.5, 294.0, null],
                      "close": [null, 294.8, null],
                      "volume":[0, 0, 0]
                    }]
                  }
                }]
              }
            }
            """;

    private MockRestServiceServer mockServer;
    private YahooExtendedHoursClient client;
    private Stock aaplStock;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        // defaultHeader 미러링 — ExtendedHoursClientConfig와 동일하게 구성
        RestClient restClient =
                builder.baseUrl(ExtendedHoursClientConfig.YAHOO_EXTENDED_HOURS_BASE_URL)
                        .defaultHeader("User-Agent", USER_AGENT)
                        .build();
        client = new YahooExtendedHoursClient(restClient);

        aaplStock =
                Stock.builder()
                        .symbol("AAPL")
                        .nameKo("애플")
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(1980, 12, 12))
                        .build();
    }

    @Nested
    @DisplayName("요청 검증 — User-Agent 헤더 및 includePrePost 쿼리 파라미터")
    class RequestValidation {

        @Test
        @DisplayName("User-Agent 헤더 포함 요청 — PRE 세션")
        void request_includesUserAgentHeader() {
            mockServer
                    .expect(header("User-Agent", USER_AGENT))
                    .andExpect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_SESSION_JSON, MediaType.APPLICATION_JSON));

            client.fetch(aaplStock, Session.PRE);

            mockServer.verify();
        }

        @Test
        @DisplayName("includePrePost=true 쿼리 파라미터 포함 요청")
        void request_includesPrePostQueryParam() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_SESSION_JSON, MediaType.APPLICATION_JSON));

            client.fetch(aaplStock, Session.PRE);

            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("PRE 세션 — 마지막 close 추출 및 referenceClose")
    class PreSession {

        @Test
        @DisplayName("PRE 세션 분봉에서 마지막 non-null close 추출")
        void preSession_extractsLastNonNullClose() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.PRE);

            assertThat(result).isPresent();
            assertThat(result.get().session()).isEqualTo(Session.PRE);
            assertThat(result.get().extPrice()).isEqualByComparingTo(new BigDecimal("295.2500"));
        }

        @Test
        @DisplayName("PRE referenceClose = chartPreviousClose")
        void preSession_referenceCloseIsChartPreviousClose() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.PRE);

            assertThat(result).isPresent();
            assertThat(result.get().referenceClose())
                    .isEqualByComparingTo(new BigDecimal("295.9500"));
        }
    }

    @Nested
    @DisplayName("AFTER 세션 — 마지막 close 추출 및 referenceClose")
    class AfterSession {

        @Test
        @DisplayName("AFTER 세션 분봉에서 마지막 non-null close 추출")
        void afterSession_extractsLastNonNullClose() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(AFTER_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.AFTER);

            assertThat(result).isPresent();
            assertThat(result.get().session()).isEqualTo(Session.AFTER);
            assertThat(result.get().extPrice()).isEqualByComparingTo(new BigDecimal("290.8025"));
        }

        @Test
        @DisplayName("AFTER referenceClose = regularMarketPrice")
        void afterSession_referenceCloseIsRegularMarketPrice() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(AFTER_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.AFTER);

            assertThat(result).isPresent();
            assertThat(result.get().referenceClose())
                    .isEqualByComparingTo(new BigDecimal("293.0800"));
        }
    }

    @Nested
    @DisplayName("skip 조건 — hasPrePostMarketData=false")
    class SkipConditions {

        @Test
        @DisplayName("hasPrePostMarketData=false → empty Optional")
        void hasPrePostMarketDataFalse_returnsEmpty() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(NO_PRE_POST_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.PRE);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("중간 null close 포함 — 역방향 탐색으로 마지막 non-null close 추출 (EC-1)")
        void middleNullClose_extractsLastNonNullReverseScan() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_MIDDLE_NULL_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.PRE);

            // close 배열: [null, 294.8, null] → 역방향: null skip, 294.8 반환
            assertThat(result).isPresent();
            assertThat(result.get().extPrice()).isEqualByComparingTo(new BigDecimal("294.8000"));
        }
    }

    @Nested
    @DisplayName("setScale(4, HALF_UP) 검증")
    class ScaleValidation {

        @Test
        @DisplayName("290.8025 → setScale(4, HALF_UP) → 290.8025 (소수점 4자리 이내 무변환)")
        void extPrice_setScale4HalfUp() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(AFTER_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.AFTER);

            assertThat(result).isPresent();
            assertThat(result.get().extPrice().scale()).isEqualTo(4);
            assertThat(result.get().referenceClose().scale()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("거래량 필드 미참조 검증")
    class VolumeNotReferenced {

        @Test
        @DisplayName("volume 배열 전부 0 — 파싱 성공, 거래량 기반 로직 없음")
        void allZeroVolume_doesNotAffectResult() {
            // PRE_SESSION_JSON의 volume이 전부 0임에도 결과 반환
            mockServer
                    .expect(
                            requestTo(
                                    "https://query2.finance.yahoo.com/v8/finance/chart/AAPL?interval=1m&range=1d&includePrePost=true"))
                    .andRespond(withSuccess(PRE_SESSION_JSON, MediaType.APPLICATION_JSON));

            Optional<ExtendedHoursRow> result = client.fetch(aaplStock, Session.PRE);

            assertThat(result).isPresent();
            // ExtendedHoursRow에 volume 필드 없음 확인
            assertThat(ExtendedHoursRow.class.getDeclaredFields())
                    .noneMatch(f -> f.getName().toLowerCase().contains("volume"));
        }
    }
}
