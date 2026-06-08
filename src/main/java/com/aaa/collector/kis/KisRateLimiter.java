package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** KIS API 호출 빈도를 제한하는 rate limiter. */
public class KisRateLimiter {

    private final Bucket bucket;
    private final Semaphore semaphore;

    public KisRateLimiter(KisProperties.RateLimit config) {
        this.bucket =
                Bucket.builder()
                        .addLimit(
                                Bandwidth.builder()
                                        .capacity(config.capacity())
                                        // refillGreedy: 경과 시간에 비례해 즉시 충전 (버스트 허용).
                                        // KIS API는 rate limit 초과 시 429 대신 500을 반환하며,
                                        // 실측 결과 25개 동시 버스트도 대부분 허용됨 → fixed-window 방식으로 판단.
                                        // 멀티 인스턴스 전환 시 Redis ProxyManager로 교체 예정 (ADR-023).
                                        .refillGreedy(
                                                config.refillPerSecond(), Duration.ofSeconds(1))
                                        .build())
                        .build();
        // fair=true: FIFO 순서 보장 — 218개 가상 스레드 경합 시 starvation 방지
        this.semaphore = new Semaphore(config.maxConcurrency(), true);
    }

    /**
     * 토큰 1개를 소비한 후 세마포어를 획득한다. 토큰이 없으면 채워질 때까지, 세마포어가 없으면 반환될 때까지 블로킹한다.
     *
     * <p>순서: bucket token 먼저, 그 다음 semaphore.acquire() — 세마포어 대기 중 토큰이 낭비되지 않도록 보장. 이 메서드가 정상 반환되면
     * 세마포어가 반드시 획득된 상태이므로, 호출자는 finally 블록에서 {@link #release()}를 호출해야 한다.
     *
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우
     */
    // @MX:NOTE: [AUTO] bucket 토큰 소비 후 semaphore 획득 — 순서 변경 금지 (토큰 먼저 → 세마포어 대기)
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-005
    public void consume() throws InterruptedException {
        bucket.asBlocking().consume(1);
        semaphore.acquire();
    }

    /**
     * 세마포어를 반환한다.
     *
     * <p><strong>호출 규약:</strong> 반드시 {@link #consume()} 호출 성공 이후 finally 블록에서만 호출해야 한다. consume()
     * 없이 호출 시 허용 슬롯이 maxConcurrency를 초과하여 동시성 제어가 무력화된다.
     *
     * @see #consume()
     */
    // @MX:WARN: [AUTO] acquire() 없이 release() 호출 시 Semaphore 슬롯이 maxConcurrency 초과 — 동시성 제어 무력화
    // @MX:REASON: 반드시 consume() 정상 반환 이후 finally 블록에서만 호출할 것
    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-005
    public void release() {
        semaphore.release();
    }
}
