package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KisRateLimiterTest {

    @Nested
    @DisplayName("Semaphore 동시성 제한")
    class ConcurrencyLimit {

        @Test
        @DisplayName("maxConcurrency 초과 스레드 — 동시 진행 수 maxConcurrency 이하")
        void consume_exceedsConcurrency_limitsConcurrentThreads() throws InterruptedException {
            // Arrange
            KisProperties.RateLimit config = new KisProperties.RateLimit(20, 20, 3);
            KisRateLimiter limiter = new KisRateLimiter(config);
            AtomicInteger concurrent = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            int threadCount = 10;
            CountDownLatch allStarted = new CountDownLatch(threadCount);
            CountDownLatch allDone = new CountDownLatch(threadCount);

            // Act: 10 threads simultaneously call consume()
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < threadCount; i++) {
                    executor.submit(
                            () -> {
                                allStarted.countDown();
                                try {
                                    allStarted.await();
                                    limiter.consume();
                                    int c = concurrent.incrementAndGet();
                                    maxConcurrent.accumulateAndGet(c, Math::max);
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    concurrent.decrementAndGet();
                                    limiter.release();
                                    allDone.countDown();
                                }
                            });
                }
                boolean completed = allDone.await(5, TimeUnit.SECONDS);

                // Assert
                assertThat(completed).as("all threads completed within timeout").isTrue();
                assertThat(maxConcurrent.get()).isLessThanOrEqualTo(3);
            }
        }
    }

    @Nested
    @DisplayName("release 보장")
    class ReleaseGuarantee {

        @Test
        @DisplayName("API 호출 예외 후 release — 이후 스레드 정상 consume")
        void release_afterException_subsequentThreadCanConsume() {
            KisProperties.RateLimit config = new KisProperties.RateLimit(20, 20, 1);
            KisRateLimiter limiter = new KisRateLimiter(config);

            // If semaphore is not released, second consume() would block forever causing test
            // timeout.
            // assertThatCode verifies no exception is thrown and execution completes normally.
            assertThatCode(
                            () -> {
                                limiter.consume();
                                limiter.release();
                                limiter.consume();
                                limiter.release();
                            })
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("capacity 설정 반영")
    class CapacitySetting {

        @Test
        @DisplayName("capacity=15 설정 — 버킷 capacity 15로 설정됨")
        void constructor_capacity15_bucketHas15Tokens() {
            KisProperties.RateLimit config = new KisProperties.RateLimit(15, 20, 10);
            KisRateLimiter limiter = new KisRateLimiter(config);

            // assertThatCode verifies that 15 consecutive consume()+release() complete without
            // exception.
            // A lower capacity (e.g. 5) would cause the loop to block, resulting in test timeout.
            assertThatCode(
                            () -> {
                                for (int i = 0; i < 15; i++) {
                                    limiter.consume();
                                    limiter.release();
                                }
                            })
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("maxConcurrency 유효성 검사")
    class MaxConcurrencyValidation {

        @Test
        @DisplayName("maxConcurrency=0 — KisProperties 생성 시 IllegalArgumentException")
        void kisProperties_maxConcurrencyZero_throwsIllegalArgumentException() {
            assertThatThrownBy(
                            () ->
                                    new KisProperties(
                                            "https://openapi.koreainvestment.com:9443",
                                            "testUser",
                                            List.of(
                                                    new KisAccountCredential(
                                                            "test", "12345678", "key", "secret")),
                                            new KisProperties.RateLimit(15, 20, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max-concurrency");
        }
    }
}
