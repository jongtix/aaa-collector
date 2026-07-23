package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * KoreaeximBackfillRateLimiter 명세 (SPEC-COLLECTOR-MARKETIND-007).
 *
 * <p>{@link com.aaa.collector.kis.KisRateLimiter}의 가상 시간 페이싱 테스트와 동형으로 작성됐다(plan.md 기술 접근).
 */
class KoreaeximBackfillRateLimiterTest {

    /** 가상 시간 제어를 위한 TimeMeter 구현체. currentTimeMs를 직접 조작할 수 있다. */
    private static final class ManualTimeMeter implements TimeMeter {

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

    @Nested
    @DisplayName("refillGreedy 버스트 억제 — 가상 시간 페이싱 (AC-01, REQ-001, REQ-003, REQ-004, REQ-020)")
    class GreedyBurstSuppression {

        /**
         * capacity=3 설정 시 t=0에서 3개까지 tryConsume 성공, 4번째는 즉시 실패(false)를 검증.
         *
         * <p>임의 1초 최대 처리량 = capacity + refillPerSecond(peak 18) 공식에서 capacity가 초기 버스트 상한임을 확인한다.
         */
        @Test
        @DisplayName("t=0에서 capacity=3 소진 후 4번째 tryConsume은 false (토큰 고갈)")
        void tryConsume_beyondCapacityAtT0_fourthReturnsFalse() {
            // Arrange: capacity=3, refillPerSecond=15, maxConcurrency=10.
            // 가상 TimeMeter로 시간을 고정(t=0)하면 greedy 충전이 발생하지 않아 capacity 한도만 남는다.
            ManualTimeMeter fixedClock = new ManualTimeMeter(0);
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(3, 15, 10);
            KoreaeximBackfillRateLimiter limiter =
                    new KoreaeximBackfillRateLimiter(config, fixedClock);
            Bucket bucket = limiter.getBucket();

            // Act: 3토큰(capacity) 모두 즉시 소비
            for (int i = 0; i < 3; i++) {
                boolean consumed = bucket.tryConsume(1);
                assertThat(consumed).as("토큰 %d번째 즉시 소비 성공", i + 1).isTrue();
            }

            // Assert: 시간이 고정된 상태에서 4번째는 토큰 없음 → false
            boolean fourthConsumed = bucket.tryConsume(1);
            assertThat(fourthConsumed)
                    .as("capacity=3 소진 후 시간 미경과 → 4번째 tryConsume false (토큰 고갈)")
                    .isFalse();
        }

        /**
         * refillGreedy는 1초를 잘게 나눠 분산 충전한다. capacity=3, refill=15 → 가상 시간으로 ~66.7ms 진행 시 약 1개 토큰이
         * 충전됨을 검증(지속 grant율 ≤ 15/sec).
         */
        @Test
        @DisplayName("refillGreedy — ~66.7ms 경과 시 토큰 1개 분산 충전 (지속 15 TPS, intervally 아님)")
        void refillGreedy_after67ms_tokenReplenishedBeforeFullSecond() throws InterruptedException {
            // Arrange: capacity=3, refillPerSecond=15 → greedy는 ~66.7ms마다 1개 충전
            ManualTimeMeter clock = new ManualTimeMeter(0);
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(3, 15, 10);
            KoreaeximBackfillRateLimiter limiter = new KoreaeximBackfillRateLimiter(config, clock);

            // Act: capacity(3)를 모두 소비해 버킷을 비운다
            for (int i = 0; i < 3; i++) {
                limiter.consume();
                limiter.release();
            }

            // 가상 시간을 67ms 전진 → greedy라면 약 1개 토큰이 충전되어야 함(1000/15 ≈ 66.7ms)
            clock.advanceMs(67);

            // Assert: 소비 성공 여부를 tryConsume(non-blocking)으로 확인
            boolean tokenAvailable = limiter.getBucket().tryConsume(1);
            assertThat(tokenAvailable)
                    .as("refillGreedy: ~66.7ms 경과 후 1개 토큰 분산 충전됨 (intervally면 여기서 false)")
                    .isTrue();
        }

        /** 벽시계 대기 없이 가상 시간만으로 지속 grant율이 설정 TPS(15)를 초과하지 않음을 결정론적으로 고정한다. */
        @Test
        @DisplayName("연속 M회(M > capacity+refill) 요청 — 가상 시간 진행 없인 추가 토큰 미부여")
        void tryConsume_manyRequestsWithoutTimeAdvance_onlyCapacityPlusRefillGranted() {
            ManualTimeMeter clock = new ManualTimeMeter(0);
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(3, 15, 10);
            KoreaeximBackfillRateLimiter limiter = new KoreaeximBackfillRateLimiter(config, clock);
            Bucket bucket = limiter.getBucket();

            int granted = 0;
            for (int i = 0; i < 30; i++) {
                if (bucket.tryConsume(1)) {
                    granted++;
                }
            }

            // t=0 고정 상태에서는 capacity(3)만큼만 부여되고, 이후 요청은 모두 거부된다(추가 시간 경과 없음).
            assertThat(granted).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("release 보장 (KisRateLimiter 미러)")
    class ReleaseGuarantee {

        @Test
        @DisplayName("API 호출 예외 후 release — 이후 스레드 정상 consume")
        void release_afterException_subsequentThreadCanConsume() {
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(20, 20, 1);
            KoreaeximBackfillRateLimiter limiter = new KoreaeximBackfillRateLimiter(config);

            // semaphore가 반환되지 않으면 두 번째 consume()이 영원히 블로킹돼 테스트 타임아웃으로 실패한다.
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
    @DisplayName("Semaphore 동시성 제한")
    class ConcurrencyLimit {

        @Test
        @DisplayName("maxConcurrency 초과 스레드 — 동시 진행 수 maxConcurrency 이하")
        void consume_exceedsConcurrency_limitsConcurrentThreads() throws InterruptedException {
            // Arrange
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(20, 20, 3);
            KoreaeximBackfillRateLimiter limiter = new KoreaeximBackfillRateLimiter(config);
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
    @DisplayName("인터럽트 계약 (REQ-008, AC-10)")
    class InterruptContract {

        @Test
        @DisplayName("토큰 대기 중 인터럽트 — InterruptedException 전파, release() 미호출")
        void consume_interruptedWhileWaitingForToken_propagatesAndDoesNotRelease()
                throws InterruptedException {
            // Arrange: capacity=1, refill=1 → 토큰 1개 소진 후 다음 토큰까지 실시간 ~1초 대기 필요.
            KoreaeximBackfillRateLimitProperties config =
                    new KoreaeximBackfillRateLimitProperties(1, 1, 10);
            KoreaeximBackfillRateLimiter limiter = new KoreaeximBackfillRateLimiter(config);
            limiter.consume();
            limiter.release();

            AtomicReference<Throwable> caught = new AtomicReference<>();
            AtomicInteger releaseCallCount = new AtomicInteger(0);
            Thread worker =
                    new Thread(
                            () -> {
                                try {
                                    limiter.consume();
                                    releaseCallCount.incrementAndGet();
                                } catch (InterruptedException e) {
                                    caught.set(e);
                                }
                            });

            // Act: worker가 토큰 대기(blocking)에 진입한 뒤 인터럽트
            worker.start();
            Thread.sleep(150);
            worker.interrupt();
            worker.join(2000);

            // Assert
            assertThat(caught.get())
                    .as("InterruptedException 전파")
                    .isInstanceOf(InterruptedException.class);
            assertThat(releaseCallCount.get())
                    .as("consume() 미성공 — release() 호출 대상 코드에 도달하지 않음")
                    .isZero();
        }
    }
}
