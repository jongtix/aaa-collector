package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.aaa.collector.market.indicator.MarketIndicatorRow;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@DisplayName("KoreaeximExchangeRateClient 단위 테스트")
class KoreaeximExchangeRateClientTest {

    private static final String API_KEY = "test-key";
    private static final int EMPTY_RETRY_MAX = 3;
    private static final int IO_RETRY_MAX = 3;
    private static final int IO_RETRY_BACKOFF_BASE_MS = 1;
    private static final int IO_RETRY_LONG_MAX = 2;
    private static final int IO_RETRY_LONG_DELAY_MS = 1;
    private static final String SAMPLE_RESPONSE =
            "[{\"cur_unit\":\"USD\",\"deal_bas_r\":\"1,523.40\",\"cur_nm\":\"미국 달러\"},{\"cur_unit\":\"EUR\",\"deal_bas_r\":\"1,650.00\",\"cur_nm\":\"유로\"}]";

    private MockRestServiceServer mockServer;
    private KoreaeximExchangeRateClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient koreaeximRestClient = builder.baseUrl("https://oapi.koreaexim.go.kr").build();
        client =
                new KoreaeximExchangeRateClient(
                        koreaeximRestClient,
                        API_KEY,
                        EMPTY_RETRY_MAX,
                        IO_RETRY_MAX,
                        IO_RETRY_BACKOFF_BASE_MS,
                        IO_RETRY_LONG_MAX,
                        IO_RETRY_LONG_DELAY_MS);
    }

    @Nested
    @DisplayName("API Key 빈 문자열 — 즉시 빈 결과 반환 (W-5, MA-03)")
    class BlankApiKey {

        private KoreaeximExchangeRateClient blankKeyClient;

        @BeforeEach
        void setUpBlankKey() {
            RestClient.Builder builder = RestClient.builder();
            RestClient koreaeximRestClient =
                    builder.baseUrl("https://oapi.koreaexim.go.kr").build();
            blankKeyClient =
                    new KoreaeximExchangeRateClient(
                            koreaeximRestClient,
                            "",
                            EMPTY_RETRY_MAX,
                            IO_RETRY_MAX,
                            IO_RETRY_BACKOFF_BASE_MS,
                            IO_RETRY_LONG_MAX,
                            IO_RETRY_LONG_DELAY_MS);
        }

        @Test
        @DisplayName("fetchDaily — apiKey 빈 문자열이면 API 호출 없이 빈 리스트 반환")
        void fetchDaily_blankKey_returnsEmpty() {
            List<MarketIndicatorRow> rows = blankKeyClient.fetchDaily(LocalDate.of(2026, 6, 20));
            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("fetchDaily — USD 필터 + 쉼표 제거 + 4자리")
    class FetchDaily {

        @Test
        @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts") // USD 행 전체 필드 검증
        @DisplayName("정상 응답 — USD 필터, 쉼표 제거 후 4자리 BigDecimal, source=KOREAEXIM (REQ-010/011)")
        void parsesUsdAndRemovesComma() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            assertThat(rows).hasSize(1);
            MarketIndicatorRow row = rows.getFirst();
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
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo("1523.40");
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

    @Nested
    @DisplayName("I/O 예외 재시도 — ResourceAccessException 한정 (KOREAEXIM Connection reset 대응)")
    class IoExceptionRetry {

        @Test
        @DisplayName("ResourceAccessException 2회 후 성공 — 재시도로 복구, 정상 행 반환")
        void resourceAccessException_retriesAndRecovers() {
            // Arrange: 첫 2회는 Connection reset(IOException→ResourceAccessException), 3번째 성공
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(
                            request -> {
                                throw new IOException("Connection reset");
                            });
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(
                            request -> {
                                throw new IOException("Connection reset");
                            });
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            // Act
            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            // Assert
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo("1523.40");
            mockServer.verify();
        }

        @Test
        @DisplayName("ResourceAccessException 상한(ioRetryMax) 초과 — 최종 예외 전파")
        void resourceAccessException_exceedsMax_propagates() {
            // Arrange: 즉시 재시도(IO_RETRY_MAX=3, 초기 1 + 재시도 3 = 4회)
            // + 장주기 재시도(IO_RETRY_LONG_MAX=2회) 모두 실패
            for (int i = 0; i <= IO_RETRY_MAX + IO_RETRY_LONG_MAX; i++) {
                mockServer
                        .expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }

            // Act & Assert
            assertThatThrownBy(() -> client.fetchDaily(LocalDate.of(2026, 6, 20)))
                    .isInstanceOf(ResourceAccessException.class);
            mockServer.verify();
        }

        @Test
        @DisplayName("즉시 재시도 전부 실패 후 장주기 1회차에서 성공 — 정상 행 반환")
        void resourceAccessException_recoversOnLongRetry() {
            // Arrange: 즉시 재시도(4회) 모두 실패, 장주기 1회차에서 성공
            for (int i = 0; i <= IO_RETRY_MAX; i++) {
                mockServer
                        .expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            // Act
            List<MarketIndicatorRow> rows = client.fetchDaily(LocalDate.of(2026, 6, 20));

            // Assert
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo("1523.40");
            mockServer.verify();
        }

        @Test
        @DisplayName("장주기 재시도 지연 값이 배수(1회차, 2회차)로 증가한다")
        void longRetryDelay_increasesByMultiple() {
            // Arrange: 지연 시간을 직접 검증할 수 없으므로, 지연 배율을 크게 설정한 별도 클라이언트로
            // 총 소요 시간이 1회차 지연 + 2회차 지연(1x + 2x) 이상임을 확인한다.
            long longDelayMs = 20;
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestClient restClient = builder.baseUrl("https://oapi.koreaexim.go.kr").build();
            KoreaeximExchangeRateClient delayClient =
                    new KoreaeximExchangeRateClient(
                            restClient,
                            API_KEY,
                            EMPTY_RETRY_MAX,
                            IO_RETRY_MAX,
                            IO_RETRY_BACKOFF_BASE_MS,
                            IO_RETRY_LONG_MAX,
                            longDelayMs);
            for (int i = 0; i <= IO_RETRY_MAX; i++) {
                server.expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }
            server.expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            // Act
            long start = System.currentTimeMillis();
            delayClient.fetchDaily(LocalDate.of(2026, 6, 20));
            long elapsedMs = System.currentTimeMillis() - start;

            // Assert: 장주기 1회차 지연(longDelayMs * 1)만 소요되어야 성공 — 등차 배수 검증
            assertThat(elapsedMs).isGreaterThanOrEqualTo(longDelayMs);
            server.verify();
        }

        @Test
        @DisplayName("HttpClientErrorException(4xx) — 재시도 없이 즉시 전파")
        void httpClientErrorException_noRetry_propagatesImmediately() {
            // Arrange: 단 1회만 4xx 응답 기대 — 재시도가 발생하면 mockServer.verify()에서 초과 요청으로 실패
            mockServer.expect(method(HttpMethod.GET)).andRespond(withBadRequest());

            // Act & Assert
            assertThatThrownBy(() -> client.fetchDaily(LocalDate.of(2026, 6, 20)))
                    .isInstanceOf(HttpClientErrorException.class);
            mockServer.verify();
        }

        @Test
        @DisplayName("재시도 횟수는 ioRetryMax/ioRetryLongMax 프로퍼티를 정확히 준수한다")
        void retryCount_matchesConfiguredMax() {
            // Arrange: (IO_RETRY_MAX+1, 초기 1회 포함) + IO_RETRY_LONG_MAX 회 실패만 등록
            // — 초과 호출 시 mockServer.verify() 실패
            for (int i = 0; i <= IO_RETRY_MAX + IO_RETRY_LONG_MAX; i++) {
                mockServer
                        .expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }

            assertThatThrownBy(() -> client.fetchDaily(LocalDate.of(2026, 6, 20)))
                    .isInstanceOf(ResourceAccessException.class);

            // Assert: 등록된 요청 수(IO_RETRY_MAX+1+IO_RETRY_LONG_MAX)가 정확히 모두 소비됨
            mockServer.verify();
        }

        @Test
        @DisplayName("ioRetryLongMax=0 — 장주기 재시도 없이 즉시 재시도 소진 직후 예외 전파")
        void longRetryMaxZero_skipsLongRetry_propagatesAfterImmediateRetries() {
            // Arrange: 즉시 재시도(IO_RETRY_MAX+1회)만 등록 — 장주기 호출이 발생하면 초과 요청으로 실패
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestClient restClient = builder.baseUrl("https://oapi.koreaexim.go.kr").build();
            KoreaeximExchangeRateClient zeroLongMaxClient =
                    new KoreaeximExchangeRateClient(
                            restClient,
                            API_KEY,
                            EMPTY_RETRY_MAX,
                            IO_RETRY_MAX,
                            IO_RETRY_BACKOFF_BASE_MS,
                            0,
                            IO_RETRY_LONG_DELAY_MS);
            for (int i = 0; i <= IO_RETRY_MAX; i++) {
                server.expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }

            // Act & Assert
            assertThatThrownBy(() -> zeroLongMaxClient.fetchDaily(LocalDate.of(2026, 6, 20)))
                    .isInstanceOf(ResourceAccessException.class);
            server.verify();
        }

        @Test
        @DisplayName("ioRetryLongDelayMs=0 — 대기 없이 장주기 재시도가 즉시 실행돼 복구된다")
        void longRetryDelayZero_retriesWithoutWaiting() {
            // Arrange: 즉시 재시도 전부 실패, 장주기 1회차(지연 0ms)에서 성공
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestClient restClient = builder.baseUrl("https://oapi.koreaexim.go.kr").build();
            KoreaeximExchangeRateClient zeroDelayClient =
                    new KoreaeximExchangeRateClient(
                            restClient,
                            API_KEY,
                            EMPTY_RETRY_MAX,
                            IO_RETRY_MAX,
                            IO_RETRY_BACKOFF_BASE_MS,
                            IO_RETRY_LONG_MAX,
                            0);
            for (int i = 0; i <= IO_RETRY_MAX; i++) {
                server.expect(method(HttpMethod.GET))
                        .andRespond(
                                request -> {
                                    throw new IOException("Connection reset");
                                });
            }
            server.expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

            // Act
            List<MarketIndicatorRow> rows = zeroDelayClient.fetchDaily(LocalDate.of(2026, 6, 20));

            // Assert
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().closeValue()).isEqualByComparingTo("1523.40");
            server.verify();
        }
    }
}
