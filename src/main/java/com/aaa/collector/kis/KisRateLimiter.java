package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;

/** KIS API 호출 빈도를 제한하는 rate limiter. */
public class KisRateLimiter {

    private final Bucket bucket;

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
    }

    /**
     * 토큰 1개를 소비한다. 토큰이 없으면 채워질 때까지 블로킹한다.
     *
     * @throws InterruptedException 대기 중 스레드가 인터럽트된 경우
     */
    public void consume() throws InterruptedException {
        bucket.asBlocking().consume(1);
    }
}
