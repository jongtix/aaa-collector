package com.aaa.collector.market.indicator.vix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import java.math.BigDecimal;
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

@DisplayName("CboeVixClient 단위 테스트")
class CboeVixClientTest {

    private static final String SAMPLE_CSV =
            """
            DATE,OPEN,HIGH,LOW,CLOSE
            01/02/2026,16.91,17.43,16.91,17.20
            01/05/2026,17.52,18.01,17.52,17.89
            """;

    private MockRestServiceServer mockServer;
    private CboeVixClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient cboeRestClient = builder.baseUrl("https://cdn.cboe.com").build();
        client = new CboeVixClient(cboeRestClient);
    }

    @Nested
    @DisplayName("fetchHistory — 전체 CSV 파싱")
    class FetchHistory {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // OHLC 4컬럼 + 날짜 + 소스 전체 검증
        @DisplayName("정상 CSV — DATE 파싱(MM/dd/yyyy), OHLC 4컬럼, source=CBOE (REQ-020/021)")
        void parsesOhlcAndDate() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_CSV, MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            assertThat(rows).hasSize(2);
            MarketIndicatorRow first = rows.getFirst();
            assertThat(first.indicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(first.tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(first.openValue()).isEqualByComparingTo("16.91");
            assertThat(first.highValue()).isEqualByComparingTo("17.43");
            assertThat(first.lowValue()).isEqualByComparingTo("16.91");
            assertThat(first.closeValue()).isEqualByComparingTo("17.20");
            assertThat(first.source()).isEqualTo("CBOE");

            MarketIndicatorRow second = rows.get(1);
            assertThat(second.tradeDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        }

        @Test
        @DisplayName("헤더 행 skip — 데이터 행만 포함")
        void skipsHeaderRow() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_CSV, MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            // 헤더(DATE,OPEN,HIGH,LOW,CLOSE)는 포함되지 않음
            assertThat(rows).noneMatch(r -> r.tradeDate() == null);
        }

        @Test
        @DisplayName("빈 CSV(헤더만) — 빈 리스트 반환")
        void emptyData_returnsEmpty() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("DATE,OPEN,HIGH,LOW,CLOSE\n", MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchDaily — 당일 행 필터")
    class FetchDaily {

        @Test
        @DisplayName("지정 날짜의 행만 반환")
        void returnsOnlyMatchingDate() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_CSV, MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 1, 2));

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().tradeDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        }

        @Test
        @DisplayName("지정 날짜 없음 — 빈 리스트")
        void noMatchingDate_returnsEmpty() {
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_CSV, MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 1, 4));

            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("행 검증 — REQ-034 skip 규칙")
    class RowValidation {

        @Test
        @DisplayName("close 0 이하 행 skip")
        void zeroClose_skipped() {
            String csv =
                    """
                    DATE,OPEN,HIGH,LOW,CLOSE
                    01/02/2026,16.91,17.43,16.91,0
                    01/05/2026,17.52,18.01,17.52,17.89
                    """;
            mockServer
                    .expect(
                            requestTo(
                                    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(csv, MediaType.TEXT_PLAIN));

            List<MarketIndicatorRow> rows = client.fetchHistory();

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo(new BigDecimal("17.89"));
        }
    }
}
