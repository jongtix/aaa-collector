package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * SPEC-COLLECTOR-SHORTSALE-OVERSEAS-002 T1 — Yahoo v8 {@code firstTradeDate} 취득 컴포넌트.
 *
 * <p>REQ-SSOG-001,003,004,006 / AC-01,03,04,06.
 */
@DisplayName("OverseasListedDateProvider 단위 테스트")
class OverseasListedDateProviderTest {

    private MockRestServiceServer mockServer;
    private OverseasListedDateProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient yahooRestClient = builder.baseUrl("https://query1.finance.yahoo.com").build();
        provider = new OverseasListedDateProvider(yahooRestClient);
    }

    private static String chartJson(long firstTradeDateEpoch) {
        return """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "symbol": "TEST",
                        "instrumentType": "EQUITY",
                        "firstTradeDate": __EPOCH__
                      }
                    }]
                  }
                }
                """
                .replace("__EPOCH__", Long.toString(firstTradeDateEpoch));
    }

    @Nested
    @DisplayName("firstTradeDate 파싱 (REQ-SSOG-001,003)")
    class ParseFirstTradeDate {

        @Test
        @DisplayName("ARM epoch 1694698200 → 2023-09-14 (America/New_York 환산)")
        void arm_epochConvertedToNewYorkDate() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andExpect(header("User-Agent", org.hamcrest.Matchers.notNullValue()))
                    .andRespond(withSuccess(chartJson(1_694_698_200L), MediaType.APPLICATION_JSON));

            LocalDate result = provider.fetch("ARM");

            assertThat(result).isEqualTo(LocalDate.of(2023, 9, 14));
            mockServer.verify();
        }

        @Test
        @DisplayName("SERV epoch 1709908200 → 2024-03-08 (EDT/EST 경계 케이스)")
        void serv_epochConvertedToNewYorkDate() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(chartJson(1_709_908_200L), MediaType.APPLICATION_JSON));

            LocalDate result = provider.fetch("SERV");

            assertThat(result).isEqualTo(LocalDate.of(2024, 3, 8));
        }
    }

    @Nested
    @DisplayName("not-found 방어 (REQ-SSOG-004)")
    class NotFound {

        @Test
        @DisplayName("HTTP 404 — 예외 미전파, null 반환")
        void httpNotFound_returnsNullWithoutException() {
            mockServer.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.NOT_FOUND));

            LocalDate result = provider.fetch("ZZZZINVALID");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("result 빈 배열 — 예외 없이 null 반환")
        void emptyResultArray_returnsNull() {
            String json = "{\"chart\":{\"result\":[]}}";
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            LocalDate result = provider.fetch("ZZZZINVALID");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("meta.firstTradeDate 없음 — 예외 없이 null 반환")
        void missingFirstTradeDate_returnsNull() {
            String json = "{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"ARKK\"}}]}}";
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

            LocalDate result = provider.fetch("ARKK");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("네트워크 예외 방어 (REQ-SSOG-004)")
    class NetworkFailure {

        @Test
        @DisplayName("네트워크 예외(IOException) — 예외 미전파, null 반환")
        void networkException_returnsNullWithoutException() {
            mockServer
                    .expect(method(HttpMethod.GET))
                    .andRespond(
                            request -> {
                                throw new IOException("connection reset");
                            });

            LocalDate result = provider.fetch("ARM");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("동시 호출 수 제한 (plan.md R3)")
    class ConcurrencyLimit {

        @Test
        @DisplayName("설정된 상한을 넘는 동시 요청이 발생하지 않는다")
        void neverExceedsConfiguredLimit() {
            int maxConcurrent = 2;
            int totalRequests = 6;
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxObserved = new AtomicInteger();

            RestClient trackingClient =
                    RestClient.builder()
                            .baseUrl("https://query1.finance.yahoo.com")
                            .requestInterceptor(
                                    (request, body, execution) -> {
                                        int now = inFlight.incrementAndGet();
                                        maxObserved.updateAndGet(m -> Math.max(m, now));
                                        try {
                                            Thread.sleep(150);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        } finally {
                                            inFlight.decrementAndGet();
                                        }
                                        return new MockClientHttpResponse(
                                                chartJson(1_694_698_200L)
                                                        .getBytes(StandardCharsets.UTF_8),
                                                HttpStatus.OK);
                                    })
                            .build();
            OverseasListedDateProvider limitedProvider =
                    new OverseasListedDateProvider(trackingClient, maxConcurrent);

            try (ExecutorService executor = Executors.newFixedThreadPool(totalRequests)) {
                List<CompletableFuture<LocalDate>> futures =
                        IntStream.range(0, totalRequests)
                                .mapToObj(
                                        i ->
                                                CompletableFuture.supplyAsync(
                                                        () -> limitedProvider.fetch("SYM" + i),
                                                        executor))
                                .toList();
                futures.forEach(CompletableFuture::join);
            }

            assertThat(maxObserved.get()).isLessThanOrEqualTo(maxConcurrent);
        }
    }
}
