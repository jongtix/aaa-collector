package com.aaa.collector.kis.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.KisRateLimiter;
import com.aaa.collector.kis.KisRateLimiterRegistry;
import com.aaa.collector.kis.token.KisAccountCredential;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
class BatchRestExecutorTest {

    private static final KisAccountCredential GOLD_CREDENTIAL =
            new KisAccountCredential("gold", "87654321", "appkey-gold", "appsecret-gold");

    @Mock private KisApiExecutor kisApiExecutor;
    @Mock private KisRateLimiterRegistry kisRateLimiterRegistry;
    @Mock private Sleeper sleeper;
    @Mock private KisRateLimiter limiter;

    private BatchRestExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BatchRestExecutor(kisApiExecutor, kisRateLimiterRegistry, sleeper);
        when(kisRateLimiterRegistry.forAlias("gold")).thenReturn(limiter);
    }

    /** Minimal KisApiResponse stub. */
    static class StubResponse implements KisApiResponse {
        @Override
        public String rtCd() {
            return "0";
        }

        @Override
        public String msgCd() {
            return "00000";
        }

        @Override
        public String msg1() {
            return "OK";
        }
    }

    @Nested
    @DisplayName("성공 경로")
    class SuccessPath {

        @Test
        @DisplayName("1회 성공 — BatchResult.isSuccess(), consume/release 각 1회 (AC-2 S2-2)")
        void execute_success_consumeAndReleaseOncePair() throws Exception {
            // Arrange
            StubResponse stubResponse = new StubResponse();
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenReturn(stubResponse);

            // Act
            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD_CREDENTIAL,
                            b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getValue()).isPresent().contains(stubResponse);
            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
        }
    }

    @Nested
    @DisplayName("EGW00201 재시도")
    class Egw00201Retry {

        @Test
        @DisplayName("EGW00201 1회 → 성공 — consume 2회, release 2회, backoff 1회 (AC-3 S3-1)")
        void execute_egw00201Once_thenSuccess_retries() throws Exception {
            // Arrange
            StubResponse stubResponse = new StubResponse();
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenReturn(stubResponse);

            // Act
            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD_CREDENTIAL,
                            b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            // Assert
            assertThat(result.isSuccess()).isTrue();
            verify(limiter, times(2)).consume();
            verify(limiter, times(2)).release();
            verify(sleeper, times(1)).sleep(any(Long.class));
        }

        @Test
        @DisplayName("EGW00201 재시도 소진(2회) — BatchResult skip, 배치 미실패 (AC-3 S3-2)")
        void execute_egw00201Exhausted_returnsSkip() throws Exception {
            // Arrange: all 3 attempts throw EGW00201 (initial + 2 retries = MAX_RETRIES+1)
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"));

            // Act
            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD_CREDENTIAL,
                            b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getSkippedSymbol()).isPresent().contains("005930");
            verify(limiter, times(BatchRestExecutor.MAX_RETRIES + 1)).consume();
            verify(limiter, times(BatchRestExecutor.MAX_RETRIES + 1)).release();
        }

        @Test
        @DisplayName("EGW00201 소진 — 정확한 skip 종목 코드 반환 (AC-3 S3-2 일부)")
        void execute_egw00201Exhausted_skippedSymbolIsCorrect() throws Exception {
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"));

            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD_CREDENTIAL,
                            b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "035720");

            assertThat(result.getSkippedSymbol()).contains("035720");
        }
    }

    @Nested
    @DisplayName("영구 오류 구분")
    class PermanentError {

        @Test
        @DisplayName("EGW00201 아닌 RuntimeException — 재시도 없이 전파 (AC-3 S3-3)")
        void execute_permanentError_propagatesWithoutRetry() throws Exception {
            // Arrange
            RuntimeException permanentError = new RuntimeException("영구 비즈니스 오류");
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(permanentError);

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            GOLD_CREDENTIAL,
                                            b -> URI.create("/test"),
                                            "TR001",
                                            StubResponse.class,
                                            "005930"))
                    .isSameAs(permanentError);

            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
            verify(sleeper, never()).sleep(any(Long.class));
        }

        @Test
        @DisplayName("AC-3.4: RestClientResponseException(5xx) — 재시도 없이 전파, sleep 없음")
        void execute_restClientResponseException_propagatesWithoutRetryOrSleep() throws Exception {
            // Arrange: 비-EGW00201 HTTP 5xx 오류 (RestClientException 하위 타입)
            RestClientResponseException httpError =
                    new RestClientResponseException(
                            "Internal Server Error", 500, "Server Error", null, null, null);
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(httpError);

            // Act & Assert: BatchResult.skip이 아닌 예외 전파 확인
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            GOLD_CREDENTIAL,
                                            b -> URI.create("/test"),
                                            "TR001",
                                            StubResponse.class,
                                            "005930"))
                    .isSameAs(httpError);

            // 재시도 없이 1회만 호출
            verify(limiter, times(1)).consume();
            verify(limiter, times(1)).release();
            verify(sleeper, never()).sleep(any(Long.class));
        }
    }

    @Nested
    @DisplayName("limiter 재경유 원칙 (REQ-BATCH-021)")
    class LimiterReRoute {

        @Test
        @DisplayName("매 재시도마다 rate limiter 재경유(consume 재호출) — retry storm 방지")
        void execute_egw00201Retry_consumeCalledPerAttempt() throws Exception {
            // Arrange: EGW00201 on first attempt, success on second
            StubResponse stubResponse = new StubResponse();
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenReturn(stubResponse);

            executor.execute(
                    GOLD_CREDENTIAL,
                    b -> URI.create("/test"),
                    "TR001",
                    StubResponse.class,
                    "005930");

            // 2 attempts → 2 consumes, 2 releases
            verify(limiter, times(2)).consume();
            verify(limiter, times(2)).release();
        }
    }

    @Nested
    @DisplayName("AC-3.3: limiter 호출 횟수 — EGW00201 소진 시 시도 횟수만큼 consume/release")
    class LimiterCallCount {

        @Test
        @DisplayName("EGW00201 3회 소진 — consume/release 각 3회(MAX_RETRIES+1)")
        void execute_egw00201Exhausted_consumeAndReleaseCalledPerAttempt() throws Exception {
            // Arrange
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"));

            // Act
            BatchResult<StubResponse> result =
                    executor.execute(
                            GOLD_CREDENTIAL,
                            b -> URI.create("/test"),
                            "TR001",
                            StubResponse.class,
                            "005930");

            // Assert
            assertThat(result.isSuccess()).isFalse();
            verify(limiter, times(BatchRestExecutor.MAX_RETRIES + 1)).consume();
            verify(limiter, times(BatchRestExecutor.MAX_RETRIES + 1)).release();
        }
    }

    @Nested
    @DisplayName("AC-3.5: InterruptedException → skip 변환 보존 (MA-2 / REQ-RETRY-016,-017)")
    class InterruptToSkip {

        @Test
        @DisplayName("인터럽트 수신 시 — BatchResult.skip 반환 (전파 아님), 인터럽트 플래그 복원")
        void execute_interrupted_returnsSkipAndRestoresFlag() throws Exception {
            // Arrange: 첫 시도에서 InterruptedException 발생
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenAnswer(
                            inv -> {
                                Thread.currentThread().interrupt();
                                throw new InterruptedException("테스트용 인터럽트");
                            });

            java.util.concurrent.atomic.AtomicBoolean interruptFlagRestored =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            BatchResult<?>[] captured = new BatchResult[1];

            Thread testThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        captured[0] =
                                                executor.execute(
                                                        GOLD_CREDENTIAL,
                                                        b -> URI.create("/test"),
                                                        "TR001",
                                                        StubResponse.class,
                                                        "005930");
                                        interruptFlagRestored.set(
                                                Thread.currentThread().isInterrupted());
                                    });

            testThread.join(5000);

            // Assert: skip 반환(전파 아님), 인터럽트 플래그 복원
            assertThat(captured[0]).isNotNull();
            assertThat(captured[0].isSuccess()).isFalse();
            assertThat(captured[0].getSkippedSymbol()).contains("005930");
            assertThat(interruptFlagRestored.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-9.2: 백오프 baseDelay=500ms 보존")
    class BackoffDelay {

        @Test
        @DisplayName("EGW00201 1회 → 성공: delay(0,500)=500ms Sleeper에 전달됨")
        void execute_egw00201Once_backoffDelay500ms() throws Exception {
            // Arrange
            StubResponse stubResponse = new StubResponse();
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenReturn(stubResponse);

            // Act
            executor.execute(
                    GOLD_CREDENTIAL,
                    b -> URI.create("/test"),
                    "TR001",
                    StubResponse.class,
                    "005930");

            // Assert: delay(0, 500) = 500ms
            verify(sleeper, times(1)).sleep(500L);
        }

        @Test
        @DisplayName("EGW00201 2회 → 성공: delay(0)=500ms, delay(1)=1000ms 순서로 Sleeper에 전달됨")
        void execute_egw00201Twice_backoffDelay500msAnd1000ms() throws Exception {
            // Arrange
            StubResponse stubResponse = new StubResponse();
            when(kisApiExecutor.executeGet(
                            eq(GOLD_CREDENTIAL), any(), anyString(), eq(StubResponse.class)))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenThrow(new KisRateLimitException("gold", "EGW00201"))
                    .thenReturn(stubResponse);

            // Act
            executor.execute(
                    GOLD_CREDENTIAL,
                    b -> URI.create("/test"),
                    "TR001",
                    StubResponse.class,
                    "005930");

            // Assert
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sleeper);
            inOrder.verify(sleeper).sleep(500L);
            inOrder.verify(sleeper).sleep(1000L);
        }
    }
}
