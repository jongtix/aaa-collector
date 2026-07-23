package com.aaa.collector.market.indicator.usdkrw;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * KOREAEXIM USDKRW 백필 rate limit 설정 (SPEC-COLLECTOR-MARKETIND-007).
 *
 * <p>{@code aaa.market-indicator.backfill.usdkrw.rate-limit} 프리픽스로 바인딩된다. 기본값(capacity=3,
 * refill-per-second=15, max-concurrency=10)은 기존 {@code kis.rate-limit} 프로덕션 설정을 그대로 미러한 값이다
 * (research §7 OQ-3 RESOLVED) — KOREAEXIM 전용으로 새로 보수화한 값이 아니다. 회차 캡(500)·호출 수는 이 설정과 무관하게 불변이며, 오직
 * 회차 내 호출 분포만 변경한다(REQ-007).
 *
 * @param capacity 버킷 용량(순간 버스트 여유) — 순간 최대 처리량 = capacity + refillPerSecond
 * @param refillPerSecond 지속 초당 토큰 충전률(연속·분산 충전, refillGreedy)
 * @param maxConcurrency 동시성 상한(fair semaphore) — 순차 백필이라 실효 no-op이나 KisRateLimiter 형태 충실도 유지
 */
@ConfigurationProperties(prefix = "aaa.market-indicator.backfill.usdkrw.rate-limit")
public record KoreaeximBackfillRateLimitProperties(
        @DefaultValue("3") int capacity,
        @DefaultValue("15") int refillPerSecond,
        @DefaultValue("10") int maxConcurrency) {

    public KoreaeximBackfillRateLimitProperties {
        List<String> errors = new ArrayList<>();
        if (capacity <= 0) {
            errors.add(
                    "aaa.market-indicator.backfill.usdkrw.rate-limit.capacity must be positive,"
                            + " got: "
                            + capacity);
        }
        if (refillPerSecond <= 0) {
            errors.add(
                    "aaa.market-indicator.backfill.usdkrw.rate-limit.refill-per-second must be"
                            + " positive, got: "
                            + refillPerSecond);
        }
        if (maxConcurrency <= 0) {
            errors.add(
                    "aaa.market-indicator.backfill.usdkrw.rate-limit.max-concurrency must be"
                            + " positive, got: "
                            + maxConcurrency);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", errors));
        }
    }
}
