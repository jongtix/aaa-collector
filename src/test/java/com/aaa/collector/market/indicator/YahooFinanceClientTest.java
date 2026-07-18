package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.market.enums.IndicatorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("YahooFinanceClient 단위 테스트")
class YahooFinanceClientTest {

    // 2026-01-02 00:00:00 ET (UTC-5) = 2026-01-02T05:00:00Z = 1767330000 UTC epoch
    // 2026-01-05 00:00:00 ET (UTC-5) = 2026-01-05T05:00:00Z = 1767589200 UTC epoch
    private static final String SAMPLE_VIX_JSON =
            """
            {
              "chart": {
                "result": [{
                  "timestamp": [1767330000, 1767589200],
                  "indicators": {
                    "quote": [{
                      "open": [16.91, 17.52],
                      "high": [17.43, 18.01],
                      "low": [16.91, 17.52],
                      "close": [17.20, 17.89]
                    }]
                  }
                }]
              }
            }
            """;

    private static final String SAMPLE_USDKRW_JSON =
            """
            {
              "chart": {
                "result": [{
                  "timestamp": [1767330000],
                  "indicators": {
                    "quote": [{
                      "open": [1380.123456],
                      "high": [1385.0],
                      "low": [1375.0],
                      "close": [1382.56789]
                    }]
                  }
                }]
              }
            }
            """;

    private MockRestServiceServer mockServer;
    private YahooFinanceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient yahooRestClient = builder.baseUrl("https://query1.finance.yahoo.com").build();
        client = new YahooFinanceClient(yahooRestClient);
    }

    @Nested
    @DisplayName("fetchHistory — VIX (^VIX)")
    class FetchHistoryVix {

        @Test
        @DisplayName("정상 응답 — OHLC 4컬럼, UTC epoch→NY LocalDate, source=YAHOO (REQ-024)")
        void parsesOhlcAndDate() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(header("User-Agent", org.hamcrest.Matchers.notNullValue()))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows).hasSize(2);
            MarketIndicatorRow first = rows.getFirst();
            assertThat(first.indicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(first.source()).isEqualTo("YAHOO");
        }

        @Test
        @DisplayName("close 소수점 4자리 반올림 (REQ-016)")
        void closeRoundedTo4Decimals() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows.getFirst().closeValue().scale()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("fetchHistory — USDKRW (USDKRW=X)")
    class FetchHistoryUsdkrw {

        @Test
        @DisplayName("USDKRW=X 심볼 — close 4자리 정밀도 (REQ-016)")
        void usdkrw_parsedWith4Decimals() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_USDKRW_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.USDKRW);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().indicatorCode()).isEqualTo(IndicatorCode.USDKRW);
            assertThat(rows.getFirst().closeValue().scale()).isEqualTo(4);
            assertThat(rows.getFirst().source()).isEqualTo("YAHOO");
        }
    }

    @Nested
    @DisplayName("fetchDaily — period1/period2 date-range 쿼리 (W-2, CR-02)")
    class FetchDailyVix {

        @Test
        @DisplayName("period1/period2 UTC epoch 파라미터 — 지정 날짜 행 반환")
        void usesDateRangeParameters() {
            LocalDate date = LocalDate.of(2026, 1, 2);
            long period1 = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long period2 = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
                                            + "?period1={p1}&period2={p2}&interval=1d",
                                    "^VIX",
                                    period1,
                                    period2))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(IndicatorCode.VIX, date);

            assertThat(rows).isNotEmpty();
            assertThat(rows.getFirst().tradeDate()).isEqualTo(date);
            mockServer.verify();
        }

        @Test
        @DisplayName("fetchHistory는 range=max URL 유지 (W-2: fetchDaily만 변경)")
        void fetchHistory_usesRangeMax() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=max&interval=1d",
                                    "^VIX"))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows).hasSize(2);
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("fetchRange — period1/period2 범위 조회 (SPEC-COLLECTOR-MARKETIND-003, AC-2)")
    class FetchRangeVix {

        @Test
        @DisplayName("period1 = from 00:00 UTC epoch, period2 = (to+1) 00:00 UTC epoch")
        void computesPeriod1AndPeriod2FromRange() {
            LocalDate from = LocalDate.of(2026, 1, 2);
            LocalDate to = LocalDate.of(2026, 1, 5);
            long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
                                            + "?period1={p1}&period2={p2}&interval=1d",
                                    "^VIX",
                                    period1,
                                    period2))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchRange(IndicatorCode.VIX, from, to);

            assertThat(rows).isNotEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("월 경계 — period2가 다음 달로 정확히 넘어간다")
        void handlesMonthBoundary() {
            LocalDate from = LocalDate.of(2026, 1, 30);
            LocalDate to = LocalDate.of(2026, 1, 31);
            long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
                                            + "?period1={p1}&period2={p2}&interval=1d",
                                    "^VIX",
                                    period1,
                                    period2))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchRange(IndicatorCode.VIX, from, to);

            assertThat(rows).isNotEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("fetchDaily(code, date)는 fetchRange(code, date, date)로 위임한다 — 단일일 요청 동일")
        void fetchDaily_delegatesToFetchRangeSameDay() {
            LocalDate date = LocalDate.of(2026, 1, 2);
            long period1 = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            long period2 = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
                                            + "?period1={p1}&period2={p2}&interval=1d",
                                    "^VIX",
                                    period1,
                                    period2))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(IndicatorCode.VIX, date);

            assertThat(rows).isNotEmpty();
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("존 분기 — 지표별 라벨링 (SPEC-COLLECTOR-MARKETIND-005 TASK-D)")
    class ZoneBranchByIndicator {

        private String singleRowJson(long epoch) {
            return """
                    {
                      "chart": {
                        "result": [{
                          "timestamp": [__EPOCH__],
                          "indicators": {
                            "quote": [{
                              "open": [1380.0],
                              "high": [1385.0],
                              "low": [1375.0],
                              "close": [1382.5]
                            }]
                          }
                        }]
                      }
                    }
                    """
                    .replace("__EPOCH__", Long.toString(epoch));
        }

        @Test
        @DisplayName("USDKRW — 런던 자정(BST) 바를 Asia/Seoul 기준 같은 거래일로 라벨링 (REQ-001, REQ-002)")
        void usdkrw_labelsWithSeoulZone() {
            // 런던 자정(BST, UTC+1) 2026-07-06T00:00:00+01:00 = UTC 2026-07-05T23:00:00Z
            long epoch = OffsetDateTime.parse("2026-07-05T23:00:00Z").toEpochSecond();
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(singleRowJson(epoch), MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.USDKRW);

            assertThat(rows.getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        }

        @Test
        @DisplayName("VIX — America/New_York 라벨 유지, 존 분기가 VIX에는 영향 없음 (REQ-003, 무회귀)")
        void vix_labelsWithNewYorkZone_unchanged() {
            // 동일 UTC epoch를 VIX로 라벨링하면 America/New_York 기준 하루 전 날짜가 된다(기존 매핑 유지)
            long epoch = OffsetDateTime.parse("2026-07-05T23:00:00Z").toEpochSecond();
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(singleRowJson(epoch), MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows.getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 7, 5));
        }

        @Test
        @DisplayName("동일 timestamp라도 지표 코드에 따라 서로 다른 존을 사용한다 (REQ-001)")
        void sameTimestamp_differentZonePerIndicator() {
            long epoch = OffsetDateTime.parse("2026-07-05T23:00:00Z").toEpochSecond();
            String json = singleRowJson(epoch);

            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            LocalDate usdkrwDate = client.fetchHistory(IndicatorCode.USDKRW).getFirst().tradeDate();
            LocalDate vixDate = client.fetchHistory(IndicatorCode.VIX).getFirst().tradeDate();

            assertThat(usdkrwDate).isNotEqualTo(vixDate);
            assertThat(usdkrwDate).isEqualTo(LocalDate.of(2026, 7, 6));
            assertThat(vixDate).isEqualTo(LocalDate.of(2026, 7, 5));
        }
    }

    @Nested
    @DisplayName("빈 result — 빈 리스트")
    class EmptyResult {

        @Test
        @DisplayName("null result — 빈 리스트 반환")
        void nullResult_returnsEmpty() {
            String json =
                    """
                    {"chart":{"result":null}}
                    """;
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("timestamp 키 없는 result — NPE 없이 빈 리스트 반환")
        void noTimestamp_returnsEmptyWithoutNpe() {
            String json =
                    """
                    {"chart":{"result":[{"indicators":{"quote":[{"close":[17.20]}]}}]}}
                    """;
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory(IndicatorCode.VIX);

            assertThat(rows).isEmpty();
        }
    }
}
