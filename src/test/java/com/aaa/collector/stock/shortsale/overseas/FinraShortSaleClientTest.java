package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("FinraShortSaleClient")
class FinraShortSaleClientTest {

    private static final String DAILY_URL =
            "https://api.finra.org/data/group/otcMarket/name/regShoDaily";
    private static final String INTEREST_URL =
            "https://api.finra.org/data/group/otcMarket/name/consolidatedShortInterest";

    private MockRestServiceServer mockServer;
    private FinraShortSaleClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.finra.org");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new FinraShortSaleClient(builder.build());
    }

    @Nested
    @DisplayName("fetchRegShoDaily — tradeReportDate EQUAL + 헤더 페이징")
    class FetchRegShoDaily {

        @Test
        @DisplayName("record-total을 읽어 offset을 limit(5000)씩 증가시키며 전 페이지를 누적한다 (AC-PAGE-1)")
        void pagesByRecordTotalHeader() {
            // Arrange: record-total=10001이면 offset 0/5000/10000 세 페이지를 받아야 한다
            //          (offset >= record-total 도달 시 종료, D13).
            String page0 = dailyRows("2026-01-06", "AAPL", 100, 200, "MSFT", 300, 400);
            String page1 = dailyRows("2026-01-06", "GOOG", 500, 600, "AMZN", 700, 800);
            String page2 = dailyRows("2026-01-06", "TSLA", 900, 1_000);

            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andRespond(
                            withSuccess(page0, MediaType.APPLICATION_JSON)
                                    .header("record-total", "10001"));
            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andExpect(jsonPath("$.offset").value(5_000))
                    .andRespond(
                            withSuccess(page1, MediaType.APPLICATION_JSON)
                                    .header("record-total", "10001"));
            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andExpect(jsonPath("$.offset").value(10_000))
                    .andRespond(
                            withSuccess(page2, MediaType.APPLICATION_JSON)
                                    .header("record-total", "10001"));

            // Act
            List<FinraRegShoDailyResponse> rows = client.fetchRegShoDaily(LocalDate.of(2026, 1, 6));

            // Assert
            mockServer.verify();
            assertThat(rows).hasSize(5);
            assertThat(rows)
                    .extracting(FinraRegShoDailyResponse::symbol)
                    .containsExactly("AAPL", "MSFT", "GOOG", "AMZN", "TSLA");
        }

        @Test
        @DisplayName("tradeReportDate EQUAL compareFilter + limit=5000을 요청 바디에 구성한다")
        void buildsEqualCompareFilter() {
            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.compareFilters[0].compareType").value("EQUAL"))
                    .andExpect(jsonPath("$.compareFilters[0].fieldName").value("tradeReportDate"))
                    .andExpect(jsonPath("$.compareFilters[0].fieldValue").value("2026-01-06"))
                    .andExpect(jsonPath("$.limit").value(5_000))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andRespond(
                            withSuccess(
                                            dailyRows("2026-01-06", "AAPL", 100, 200),
                                            MediaType.APPLICATION_JSON)
                                    .header("record-total", "1"));

            // Act
            client.fetchRegShoDaily(LocalDate.of(2026, 1, 6));

            // Assert
            mockServer.verify();
        }

        @Test
        @DisplayName("Authorization 헤더를 포함하지 않는다 (AC-AUTH-1, REQ-SSO-005/-006)")
        void sendsNoAuthorizationHeader() {
            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                    .andRespond(
                            withSuccess(
                                            dailyRows("2026-01-06", "AAPL", 100, 200),
                                            MediaType.APPLICATION_JSON)
                                    .header("record-total", "1"));

            // Act
            client.fetchRegShoDaily(LocalDate.of(2026, 1, 6));

            // Assert
            mockServer.verify();
        }

        @Test
        @DisplayName("record-total=0(빈 응답)이면 빈 리스트를 반환한다 (AC-EMPTY-1)")
        void returnsEmptyOnZeroRecordTotal() {
            mockServer
                    .expect(requestTo(DAILY_URL))
                    .andRespond(
                            withSuccess("[]", MediaType.APPLICATION_JSON)
                                    .header("record-total", "0"));

            // Act
            List<FinraRegShoDailyResponse> rows = client.fetchRegShoDaily(LocalDate.of(2026, 1, 6));

            // Assert
            mockServer.verify();
            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchConsolidatedShortInterest — dateRangeFilters 범위 + 헤더 페이징")
    class FetchConsolidatedShortInterest {

        @Test
        @DisplayName("dateRangeFilters(settlementDate, start/end) + limit/offset을 요청 바디에 구성한다")
        void buildsDateRangeFilter() {
            mockServer
                    .expect(requestTo(INTEREST_URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                    .andExpect(jsonPath("$.dateRangeFilters[0].fieldName").value("settlementDate"))
                    .andExpect(jsonPath("$.dateRangeFilters[0].startDate").value("2026-05-10"))
                    .andExpect(jsonPath("$.dateRangeFilters[0].endDate").value("2026-06-19"))
                    .andExpect(jsonPath("$.limit").value(5_000))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andRespond(
                            withSuccess(
                                            interestRows("AAPL", "2026-04-15", 134_422_787),
                                            MediaType.APPLICATION_JSON)
                                    .header("record-total", "1"));

            // Act
            List<FinraConsolidatedShortInterestResponse> rows =
                    client.fetchConsolidatedShortInterest(
                            LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 19));

            // Assert
            mockServer.verify();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().symbolCode()).isEqualTo("AAPL");
            assertThat(rows.getFirst().currentShortPositionQuantity())
                    .isEqualByComparingTo("134422787");
        }

        @Test
        @DisplayName("record-total을 읽어 offset을 limit(5000)씩 증가시키며 전 페이지를 누적한다 (AC-PAGE-1)")
        void pagesByRecordTotalHeader() {
            String page0 =
                    interestRows("AAPL", "2026-04-15", 134_422_787, "MSFT", "2026-04-15", 50_000);
            String page1 = interestRows("GOOG", "2026-04-30", 60_000);

            mockServer
                    .expect(requestTo(INTEREST_URL))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andRespond(
                            withSuccess(page0, MediaType.APPLICATION_JSON)
                                    .header("record-total", "5001"));
            mockServer
                    .expect(requestTo(INTEREST_URL))
                    .andExpect(jsonPath("$.offset").value(5_000))
                    .andRespond(
                            withSuccess(page1, MediaType.APPLICATION_JSON)
                                    .header("record-total", "5001"));

            // Act
            List<FinraConsolidatedShortInterestResponse> rows =
                    client.fetchConsolidatedShortInterest(
                            LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 19));

            // Assert
            mockServer.verify();
            assertThat(rows).hasSize(3);
            assertThat(rows)
                    .extracting(FinraConsolidatedShortInterestResponse::symbolCode)
                    .containsExactly("AAPL", "MSFT", "GOOG");
        }

        @Test
        @DisplayName("record-total=0이면 빈 리스트를 반환한다 (AC-EMPTY-1)")
        void returnsEmptyOnZeroRecordTotal() {
            mockServer
                    .expect(requestTo(INTEREST_URL))
                    .andRespond(
                            withSuccess("[]", MediaType.APPLICATION_JSON)
                                    .header("record-total", "0"));

            // Act
            List<FinraConsolidatedShortInterestResponse> rows =
                    client.fetchConsolidatedShortInterest(
                            LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 19));

            // Assert
            mockServer.verify();
            assertThat(rows).isEmpty();
        }
    }

    // --- fixture helpers ---

    /** {@code (symbol, short, total)} 트리플의 가변 인자로 regShoDaily JSON 배열을 만든다. */
    private static String dailyRows(String tradeReportDate, Object... symbolShortTotalTriples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < symbolShortTotalTriples.length; i += 3) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(
                    """
                    {"tradeReportDate":"%s","securitiesInformationProcessorSymbolIdentifier":"%s","shortParQuantity":%s,"totalParQuantity":%s}"""
                            .formatted(
                                    tradeReportDate,
                                    symbolShortTotalTriples[i],
                                    symbolShortTotalTriples[i + 1],
                                    symbolShortTotalTriples[i + 2]));
        }
        return sb.append(']').toString();
    }

    /**
     * {@code (symbol, settlementDate, currentShortPosition)} 트리플의 가변 인자로 consolidatedShortInterest
     * JSON 배열을 만든다.
     */
    private static String interestRows(Object... symbolDateQtyTriples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < symbolDateQtyTriples.length; i += 3) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(
                    """
                    {"symbolCode":"%s","settlementDate":"%s","currentShortPositionQuantity":%s,"revisionFlag":null}"""
                            .formatted(
                                    symbolDateQtyTriples[i],
                                    symbolDateQtyTriples[i + 1],
                                    symbolDateQtyTriples[i + 2]));
        }
        return sb.append(']').toString();
    }
}
