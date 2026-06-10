package com.aaa.collector.kis;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 앱키별 독립 {@link KisRateLimiter} 인스턴스를 보유하는 레지스트리.
 *
 * <p>모든 인스턴스는 동일한 {@link KisProperties.RateLimit} 설정(capacity / refill-per-second /
 * max-concurrency)으로 생성되며, 키별로 독립 Bucket + Semaphore 2축 제어를 수행한다.
 *
 * @see KisRateLimiter
 */
// @MX:ANCHOR: [AUTO] 배치 경로의 per-key rate limiting 진입점 — 모든 멀티키 호출이 이 레지스트리를 통해 limiter를 획득
// @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-010 — 앱키별 독립 limiter 레지스트리
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001
public class KisRateLimiterRegistry {

    private final Map<String, KisRateLimiter> registry;

    /**
     * {@link KisProperties#accounts()} 목록을 순회하여 alias별 {@link KisRateLimiter}를 생성한다.
     *
     * @param kisProperties KIS 설정 (accounts + rateLimit)
     */
    public KisRateLimiterRegistry(KisProperties kisProperties) {
        this.registry =
                kisProperties.accounts().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        KisAccountCredential::alias,
                                        account -> new KisRateLimiter(kisProperties.rateLimit())));
    }

    /**
     * 지정된 alias의 {@link KisRateLimiter}를 반환한다.
     *
     * @param alias 계좌 별칭 (예: isa, gold, pension, stock, dc)
     * @return 해당 alias의 rate limiter
     * @throws IllegalArgumentException 등록되지 않은 alias인 경우
     */
    public KisRateLimiter forAlias(String alias) {
        KisRateLimiter limiter = registry.get(alias);
        if (limiter == null) {
            throw new IllegalArgumentException("등록되지 않은 alias: " + alias);
        }
        return limiter;
    }
}
