package com.aaa.collector.common.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.token.KisApiResponseException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@DisplayName("RetryExecutor")
class RetryExecutorTest {

    /**
     * KIS 기본 재시도 정책: KisApiResponseException / KisApiBusinessException은 permanent(즉시 전파),
     * KisRateLimitException / RestClientException(하위타입 포함)은 retryable.
     *
     * <p>RetryExecutor는 KIS 클래스에 의존하지 않으므로, 이 정책은 테스트 헬퍼에서 정의한다. 실제 호출부(BatchRestExecutor,
     * KisWatchlistClient)도 동일한 패턴으로 predicate를 주입한다.
     */
    private static final Predicate<RuntimeException> KIS_DEFAULT_RETRYABLE =
            ex ->
                    !(ex instanceof KisApiResponseException)
                            && !(ex instanceof KisApiBusinessException)
                            && (ex instanceof KisRateLimitException
                                    || ex instanceof RestClientException);

    /** 2회차에서 성공하도록 callCount 분기에 사용하는 상수. */
    private static final int FIRST_ATTEMPT = 1;

    private Sleeper sleeper;

    @BeforeEach
    void setUp() throws InterruptedException {
        sleeper = mock(Sleeper.class);
    }

    private RetryExecutor executorWithMaxAttempts(int maxAttempts) {
        return new RetryExecutor(maxAttempts, 500L, sleeper, KIS_DEFAULT_RETRYABLE);
    }

    // ── AC-1.1: KisRateLimitException(EGW00201) retryable ─────────────────────

    @Nested
    @DisplayName("AC-1.1: KisRateLimitException — retryable")
    class RateLimitRetry {

        @Test
        @DisplayName("항상 KisRateLimitException — 3회 호출, Sleeper 2회, 마지막 예외 전파")
        void alwaysRateLimit_retriesAndPropagates() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);
            KisRateLimitException ex = new KisRateLimitException("alias", "EGW00201");

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                callCount.incrementAndGet();
                                                throw ex;
                                            }))
                    .isSameAs(ex);

            assertThat(callCount.get()).isEqualTo(3);
            verify(sleeper, times(2)).sleep(anyLong());
        }
    }

    // ── AC-1.2: bare RestClientException retryable ────────────────────────────

    @Nested
    @DisplayName("AC-1.2: RestClientException — retryable")
    class RestClientRetry {

        @Test
        @DisplayName("항상 RestClientException — 3회 호출, 예외 전파")
        void alwaysRestClientException_retriesAndPropagates() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                callCount.incrementAndGet();
                                                throw new RestClientException("서버 오류");
                                            }))
                    .isInstanceOf(RestClientException.class);

            assertThat(callCount.get()).isEqualTo(3);
        }
    }

    // ── AC-1.2b: RestClientResponseException(하위타입) retryable ──────────────

    @Nested
    @DisplayName("AC-1.2b: RestClientResponseException(RestClientException 하위타입) — retryable")
    class RestClientResponseRetry {

        @Test
        @DisplayName("RestClientResponseException — 분류기가 retryable 판정, 3회 호출")
        void restClientResponseException_retriedAsSubtype() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);
            RestClientResponseException ex =
                    new RestClientResponseException(
                            "5xx 오류",
                            org.springframework.http.HttpStatusCode.valueOf(500),
                            "Internal Server Error",
                            null,
                            null,
                            null);

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                callCount.incrementAndGet();
                                                throw ex;
                                            }))
                    .isInstanceOf(RestClientResponseException.class);

            assertThat(callCount.get()).isEqualTo(3);
        }
    }

    // ── AC-1.3: KisApiResponseException — permanent ────────────────────────────

    @Nested
    @DisplayName("AC-1.3: KisApiResponseException — permanent (즉시 전파)")
    class ApiResponseExceptionPermanent {

        @Test
        @DisplayName("KisApiResponseException — 1회 호출, Sleeper 미호출, 즉시 전파")
        void kisApiResponseException_propagatesImmediately() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            KisApiResponseException ex = new KisApiResponseException("alias", "검증 실패");

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                throw ex;
                                            }))
                    .isSameAs(ex);

            verify(sleeper, never()).sleep(anyLong());
        }

        @Test
        @DisplayName("KisApiResponseException — 정확히 1회만 작업 호출됨")
        void kisApiResponseException_calledExactlyOnce() {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                callCount.incrementAndGet();
                                                throw new KisApiResponseException("alias", "검증 실패");
                                            }))
                    .isInstanceOf(KisApiResponseException.class);

            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    // ── AC-1.4: KisApiBusinessException — permanent ───────────────────────────

    @Nested
    @DisplayName("AC-1.4: KisApiBusinessException — permanent (즉시 전파)")
    class BusinessExceptionPermanent {

        @Test
        @DisplayName("KisApiBusinessException — 1회 호출, 즉시 전파")
        void kisApiBusinessException_propagatesImmediately() {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                callCount.incrementAndGet();
                                                throw new KisApiBusinessException(
                                                        "1", "EGW00123", "비즈니스 오류");
                                            }))
                    .isInstanceOf(KisApiBusinessException.class);

            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    // ── AC-1.5: 성공 시 무재시도 ──────────────────────────────────────────────

    @Nested
    @DisplayName("AC-1.5: 성공 시 무재시도")
    class SuccessNoRetry {

        @Test
        @DisplayName("첫 시도 성공 — 1회 호출, Sleeper 미호출, 결과 반환")
        void success_calledOnceAndReturnsResult() throws Exception {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            String expected = "결과값";

            // Act
            String result = executor.execute(() -> expected);

            // Assert
            assertThat(result).isEqualTo(expected);
            verify(sleeper, never()).sleep(anyLong());
        }
    }

    // ── Edge case: 2회차 성공 ────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge case: 2회차 성공")
    class SecondAttemptSuccess {

        @Test
        @DisplayName("1회차 KisRateLimitException 후 2회차 성공 — 2회 호출, Sleeper 1회, 결과 반환")
        void firstFailThenSuccess_retriesOnceAndReturnsResult() throws Exception {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);
            AtomicInteger callCount = new AtomicInteger(0);
            String expected = "성공 결과";

            // Act
            String result =
                    executor.execute(
                            () -> {
                                if (callCount.incrementAndGet() == FIRST_ATTEMPT) {
                                    throw new KisRateLimitException("alias", "EGW00201");
                                }
                                return expected;
                            });

            // Assert
            assertThat(result).isEqualTo(expected);
            assertThat(callCount.get()).isEqualTo(2);
            verify(sleeper, times(1)).sleep(anyLong());
        }
    }

    // ── Edge case: Sleeper 백오프 지연 인자 검증 (AC-9) ──────────────────────

    @Nested
    @DisplayName("백오프 지연 인자 검증")
    class BackoffDelayArguments {

        @Test
        @DisplayName("baseDelay=500ms 기준 — delay(0)=500ms, delay(1)=1000ms")
        void backoffDelays_matchExponentialBackoff() throws Exception {
            // Arrange
            Sleeper spySleeper = mock(Sleeper.class);
            RetryExecutor executor = new RetryExecutor(3, 500L, spySleeper, KIS_DEFAULT_RETRYABLE);

            // Act: 3회 모두 실패하여 2번 sleep 발생
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                throw new KisRateLimitException(
                                                        "alias", "EGW00201");
                                            }))
                    .isInstanceOf(KisRateLimitException.class);

            // Assert: delay(0,500)=500ms, delay(1,500)=1000ms
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(spySleeper);
            inOrder.verify(spySleeper).sleep(500L);
            inOrder.verify(spySleeper).sleep(1000L);
        }
    }

    // ── Edge case: InterruptedException 처리 (REQ-RETRY-016) ─────────────────

    @Nested
    @DisplayName("InterruptedException — 인터럽트 플래그 복원 후 전파")
    class InterruptHandling {

        @Test
        @DisplayName("Sleeper sleep 중 InterruptedException — 플래그 복원 후 전파")
        void sleepInterrupted_restoresFlagAndPropagates() throws InterruptedException {
            // Arrange
            Sleeper interruptibleSleeper =
                    millis -> {
                        throw new InterruptedException("테스트용 인터럽트");
                    };
            RetryExecutor executor =
                    new RetryExecutor(3, 500L, interruptibleSleeper, KIS_DEFAULT_RETRYABLE);
            AtomicInteger interruptFlagRestored = new AtomicInteger(0);

            // Act: 가상 스레드에서 실행하여 인터럽트 플래그 복원 검증
            Thread testThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            executor.execute(
                                                    () -> {
                                                        throw new KisRateLimitException(
                                                                "alias", "EGW00201");
                                                    });
                                        } catch (InterruptedException e) {
                                            if (Thread.currentThread().isInterrupted()) {
                                                interruptFlagRestored.set(1);
                                            }
                                        }
                                    });

            testThread.join(5000);

            // Assert: 인터럽트 플래그가 복원되고 InterruptedException이 전파됨
            assertThat(interruptFlagRestored.get()).isEqualTo(1);
        }
    }

    // ── Sleeper 호출 횟수 검증 (maxAttempts-1회) ─────────────────────────────

    @Nested
    @DisplayName("Sleeper 호출 횟수 = maxAttempts-1")
    class SleeperCallCount {

        @Test
        @DisplayName("maxAttempts=3, 3회 모두 실패 — Sleeper 정확히 2회 호출")
        void allAttemptsFail_sleeperCalledMaxAttemptsMinus1() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(3);

            // Act
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                throw new KisRateLimitException(
                                                        "alias", "EGW00201");
                                            }))
                    .isInstanceOf(KisRateLimitException.class);

            // Assert
            verify(sleeper, times(2)).sleep(anyLong());
        }

        @Test
        @DisplayName("마지막 시도 후에는 sleep 미호출 (소진 직후 즉시 전파)")
        void lastAttemptFails_noSleepAfterExhaustion() throws InterruptedException {
            // Arrange
            RetryExecutor executor = executorWithMaxAttempts(2);

            // Act
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            () -> {
                                                throw new KisRateLimitException(
                                                        "alias", "EGW00201");
                                            }))
                    .isInstanceOf(KisRateLimitException.class);

            // Assert: maxAttempts=2 → 1회 sleep
            verify(sleeper, times(1)).sleep(anyLong());
        }
    }
}
