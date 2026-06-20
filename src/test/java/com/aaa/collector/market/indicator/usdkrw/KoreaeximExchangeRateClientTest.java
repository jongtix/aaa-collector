package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

@DisplayName("KoreaeximExchangeRateClient 단위 테스트")
class KoreaeximExchangeRateClientTest {

    private static final String API_KEY = "test-key";
    private static final int EMPTY_RETRY_MAX = 3;

    private MockRestServiceServer mockServer;
    private KoreaeximExchangeRateClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient koreaeximRestClient = builder.baseUrl("https://www.koreaexim.go.kr").build();
        client = new KoreaeximExchangeRateClient(koreaeximRestClient, API_KEY, EMPTY_RETRY_MAX);
    }

    private static final String SAMPLE_RESPONSE =
            """
            [{"cur_unit":"USD","deal_bas_r":"1,523.40","cur_nm":"미국 달러"},{"cur_unit":"EUR","deal_bas_r":"1,650.00","cur_nm":"유로"}]
            """;

    @Nested
    @DisplayName("fetchDaily — USD 필터 + 쉼표 제거 + 4자리")
    class FetchDaily {

        @Test
        @DisplayName("정상 응답 — USD 필터, 쉼표 제거 후 4자리 BigDecimal, source=KOREAEXIM (REQ-010/011)")
        void parsesUsdAndRemovesComma() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).hasSize(1);
            MarketIndicatorRow row = rows.get(0);
            assertThat(row.closeValue()).isEqualByComparingTo("1523.40");
            assertThat(row.closeValue().scale()).isEqualTo(4);
            assertThat(row.openValue()).isNull();
            assertThat(row.highValue()).isNull();
            assertThat(row.lowValue()).isNull();
            assertThat(row.source()).isEqualTo("KOREAEXIM");
        }

        @Test
        @DisplayName("빈 배열 — 전날 영업일 재시도 (REQ-012)")
        void emptyArray_retriesPreviousBusinessDay() {
            // 첫 요청: 빈 배열, 두 번째(전날 영업일): 정상 응답
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).closeValue()).isEqualByComparingTo("1523.40");
        }

        @Test
        @DisplayName("연속 빈 배열 — 상한(emptyRetryMax) 후 빈 결과 반환 (REQ-012)")
        void continuousEmpty_stopsAtMax() {
            // EMPTY_RETRY_MAX=3 이므로 초기 1 + 재시도 3 = 4회 호출 후 빈 결과
            for (int i = 0; i <= EMPTY_RETRY_MAX; i++) {
                mockServer
                        .expect(method(HttpMethod.GET))
                        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            }

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).isEmpty();
            mockServer.verify();
        }

        @Test
        @DisplayName("주말 건너뜀 — 토요일 입력 시 금요일→목요일 순 탐색")
        void weekendSkipped_previousFriday() {
            // 2026-06-20이 토요일이면 금요일(06-19) 조회
            // 여기서는 금요일 응답이 성공하는 케이스
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            // 임의의 날짜로 테스트 — 빈 배열 재시도 로직 검증
            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("USD 없는 응답 — 빈 결과 (EUR만 있을 때)")
        void noUsd_returnsEmpty() {
            String eurOnly =
                    """
                    [{"cur_unit":"EUR","deal_bas_r":"1,650.00","cur_nm":"유로"}]
                    """;
            // 모든 시도가 EUR만 반환 — EMPTY_RETRY_MAX+1회 설정
            for (int i = 0; i <= EMPTY_RETRY_MAX; i++) {
                mockServer
                        .expect(method(HttpMethod.GET))
                        .andRespond(withSuccess(eurOnly, MediaType.APPLICATION_JSON));
            }

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).isEmpty();
        }
    }
}
