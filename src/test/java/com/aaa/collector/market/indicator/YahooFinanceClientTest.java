package com.aaa.collector.market.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.market.enums.IndicatorCode;
import java.time.LocalDate;
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

    private MockRestServiceServer mockServer;
    private YahooFinanceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient yahooRestClient = builder.baseUrl("https://query1.finance.yahoo.com").build();
        client = new YahooFinanceClient(yahooRestClient);
    }

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

    @Nested
    @DisplayName("fetchHistory — VIX (^VIX / %5EVIX)")
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
            MarketIndicatorRow first = rows.get(0);
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

            assertThat(rows.get(0).closeValue().scale()).isEqualTo(4);
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
            assertThat(rows.get(0).indicatorCode()).isEqualTo(IndicatorCode.USDKRW);
            assertThat(rows.get(0).closeValue().scale()).isEqualTo(4);
            assertThat(rows.get(0).source()).isEqualTo("YAHOO");
        }
    }

    @Nested
    @DisplayName("fetchDaily — 날짜 필터")
    class FetchDailyVix {

        @Test
        @DisplayName("지정 날짜 행 반환")
        void returnsMatchingDate() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_VIX_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows =
                    client.fetchDaily(IndicatorCode.VIX, LocalDate.of(2026, 1, 2));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
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
    }
}
