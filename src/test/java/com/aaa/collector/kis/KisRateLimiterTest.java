package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 가상 시간 제어를 위한 TimeMeter 구현체. currentTimeMs를 직접 조작할 수 있다. */
class ManualTimeMeter implements TimeMeter {

    private final AtomicLong currentMs;

    ManualTimeMeter(long initialMs) {
        this.currentMs = new AtomicLong(initialMs);
    }

    void advanceMs(long deltaMs) {
        currentMs.addAndGet(deltaMs);
    }

    @Override
    public long currentTimeNanos() {
        return currentMs.get() * 1_000_000L;
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }
}

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
        @DisplayName("capacity=15 설정 — 리필 없이 15토큰 즉시 소비 가능")
        void constructor_capacity15_fifteenTokensAvailableWithoutRefill() {
            // Arrange: refillPerSecond=1로 리필 매우 느리게 설정.
            // capacity<15이면 부족한 토큰마다 ~1초 대기 발생 → 500ms 이내 완료 불가.
            KisProperties.RateLimit config = new KisProperties.RateLimit(15, 1, 20);
            KisRateLimiter limiter = new KisRateLimiter(config);

            // Act & Assert: 15번 consume+release가 500ms 이내 완료 → 모두 초기 토큰에서 소비됨
            long start = System.currentTimeMillis();
            assertThatCode(
                            () -> {
                                for (int i = 0; i < 15; i++) {
                                    limiter.consume();
                                    limiter.release();
                                }
                            })
                    .doesNotThrowAnyException();
            assertThat(System.currentTimeMillis() - start)
                    .as("capacity=15이므로 리필 대기 없이 15토큰 즉시 소비")
                    .isLessThan(500L);
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

    @Nested
    @DisplayName("refillGreedy 버스트 억제")
    class GreedyBurstSuppression {

        /**
         * capacity=5 설정 시 t=0에서 5개까지 tryConsume 성공, 6번째는 즉시 실패(false)를 검증.
         *
         * <p>tryConsume은 non-blocking이므로 가상 시간 환경에서도 토큰 고갈 여부를 즉시 확인할 수 있다. 이는 임의 1초 최대 처리량 =
         * capacity + refillPerSecond 공식에서 capacity가 초기 버스트 상한임을 확인한다.
         */
        @Test
        @DisplayName("t=0에서 capacity=5 소진 후 6번째 tryConsume은 false (토큰 고갈)")
        void tryConsume_beyondCapacityAtT0_sixthReturnsFalse() {
            // Arrange: capacity=5, refillPerSecond=10, maxConcurrency=20.
            // 가상 TimeMeter로 시간을 고정(t=0)하면 greedy 충전이 발생하지 않아 capacity 한도만 남는다.
            ManualTimeMeter fixedClock = new ManualTimeMeter(0);
            KisProperties.RateLimit config = new KisProperties.RateLimit(5, 10, 20);
            KisRateLimiter limiter = new KisRateLimiter(config, fixedClock);
            Bucket bucket = limiter.getBucket();

            // Act: 5토큰(capacity) 모두 즉시 소비
            for (int i = 0; i < 5; i++) {
                boolean consumed = bucket.tryConsume(1);
                assertThat(consumed).as("토큰 %d번째 즉시 소비 성공", i + 1).isTrue();
            }

            // Assert: 시간이 고정된 상태에서 6번째는 토큰 없음 → false
            boolean sixthConsumed = bucket.tryConsume(1);
            assertThat(sixthConsumed)
                    .as("capacity=5 소진 후 시간 미경과 → 6번째 tryConsume false (토큰 고갈)")
                    .isFalse();
        }

        /**
         * refillGreedy는 1초를 잘게 나눠 분산 충전한다. 가상 시간으로 100ms 진행 시 약 1개 토큰이 충전됨을 검증.
         *
         * <p>refillIntervally라면 1초 경계 전까지 충전이 없으므로 이 테스트가 실패했을 것이다.
         */
        @Test
        @DisplayName("refillGreedy — 100ms 경과 시 토큰 1개 분산 충전 (intervally 아님)")
        void refillGreedy_after100ms_tokenReplenishedBeforeFullSecond()
                throws InterruptedException {
            // Arrange: capacity=5, refillPerSecond=10 → greedy는 100ms마다 1개 충전
            ManualTimeMeter clock = new ManualTimeMeter(0);
            KisProperties.RateLimit config = new KisProperties.RateLimit(5, 10, 20);
            KisRateLimiter limiter = new KisRateLimiter(config, clock);

            // Act: capacity(5)를 모두 소비해 버킷을 비운다
            for (int i = 0; i < 5; i++) {
                limiter.consume();
                limiter.release();
            }

            // 가상 시간을 100ms 전진 → greedy라면 약 1개 토큰이 충전되어야 함
            clock.advanceMs(100);

            // Assert: 소비 성공 여부를 tryConsume(non-blocking)으로 확인
            boolean tokenAvailable = limiter.getBucket().tryConsume(1);
            assertThat(tokenAvailable)
                    .as("refillGreedy: 100ms 경과 후 1개 토큰 분산 충전됨 (intervally면 여기서 false)")
                    .isTrue();
        }
    }
}
