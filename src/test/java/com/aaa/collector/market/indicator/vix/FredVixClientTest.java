package com.aaa.collector.market.indicator.vix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
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

@DisplayName("FredVixClient 단위 테스트")
class FredVixClientTest {

    private static final String FRED_API_KEY = "test-key";

    private static final String SAMPLE_JSON =
            """
            {
              "observations": [
                {"date":"2026-01-02","value":"17.20"},
                {"date":"2026-01-05","value":"17.89"},
                {"date":"2026-01-06","value":"."}
              ]
            }
            """;

    private MockRestServiceServer mockServer;
    private FredVixClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient fredRestClient = builder.baseUrl("https://api.stlouisfed.org").build();
        client = new FredVixClient(fredRestClient, FRED_API_KEY);
    }

    @Nested
    @DisplayName("fetchHistory — FRED JSON 파싱")
    class FetchHistory {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // OHLC null 포함 전체 필드 검증
        @DisplayName("정상 응답 — date 파싱, close만/open_high_low=NULL, source=FRED (REQ-023)")
        void parsesDateAndClose() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            // '.' 결측 행 제외 — 2건
            assertThat(rows).hasSize(2);
            MarketIndicatorRow first = rows.getFirst();
            assertThat(first.indicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(first.closeValue()).isEqualByComparingTo("17.20");
            assertThat(first.openValue()).isNull();
            assertThat(first.highValue()).isNull();
            assertThat(first.lowValue()).isNull();
            assertThat(first.source()).isEqualTo("FRED");
        }

        @Test
        @DisplayName("'.' 결측값 행 skip (REQ-023)")
        void dotMissing_rowSkipped() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            // "2026-01-06" value="." → skip
            assertThat(rows).noneMatch(r -> r.tradeDate().equals(LocalDate.of(2026, 1, 6)));
        }

        @Test
        @DisplayName("빈 observations — 빈 리스트")
        void emptyObservations_returnsEmpty() {
            String json =
                    """
                    {"observations":[]}
                    """;
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchDaily — date-range 쿼리 (W-2, CR-02)")
    class FetchDaily {

        private static final String SINGLE_DAY_JSON =
                """
                {
                  "observations": [
                    {"date":"2026-01-02","value":"17.20"}
                  ]
                }
                """;

        @Test
        @DisplayName("observation_start/end 단일 날짜 쿼리 — 해당 날짜 행 반환")
        void singleDateQuery_returnsMatchingRow() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://api.stlouisfed.org/fred/series/observations"
                                            + "?series_id=VIXCLS&file_type=json"
                                            + "&observation_start={start}&observation_end={end}&api_key={key}",
                                    "2026-01-02",
                                    "2026-01-02",
                                    FRED_API_KEY))
                    .andRespond(withSuccess(SINGLE_DAY_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 1, 2));

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo("17.20");
            mockServer.verify();
        }

        @Test
        @DisplayName("전체 이력(fetchHistory)은 limit=100000 URL 유지 (W-2: fetchDaily만 변경)")
        void fetchHistory_usesFullHistoryPath() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(
                            requestToUriTemplate(
                                    "https://api.stlouisfed.org/fred/series/observations"
                                            + "?series_id=VIXCLS&file_type=json&limit=100000&api_key={key}",
                                    FRED_API_KEY))
                    .andRespond(withSuccess(SAMPLE_JSON, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            assertThat(rows).hasSize(2);
            mockServer.verify();
        }
    }
}
