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
        this.semaphore = new Semaphore(config.maxConcurrency());
    }

    /**
     * 토큰 1개를 소비한 후 세마포어를 획득한다. 토큰이 없으면 채워질 때까지, 세마포어가 없으면 반환될 때까지 블로킹한다.
     *
     * <p>순서: bucket token 먼저, 그 다음 semaphore.acquire() — 세마포어 대기 중 토큰이 낭비되지 않도록 보장.
     *
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우
     */
    public void consume() throws InterruptedException {
        bucket.asBlocking().consume(1);
        semaphore.acquire();
    }

    /** 세마포어를 반환한다. API 호출 완료 후 반드시 finally 블록에서 호출해야 한다. */
    public void release() {
        semaphore.release();
    }
}
