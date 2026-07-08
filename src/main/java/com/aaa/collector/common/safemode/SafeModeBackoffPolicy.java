package com.aaa.collector.common.safemode;

import java.time.Duration;

/**
 * SafeMode 재진입 백오프 TTL 산정 정책(SPEC-COLLECTOR-SAFEMODE-001 D-E).
 *
 * <p>백오프 레벨(0부터 시작)에 대해 {@code TTL = min(initialTtl * 2^level, maxTtl)}로 TTL을 계산한다. 레벨이 상한을 넘어서도
 * TTL은 {@code maxTtl}에서 고정된다(1h → 2h → 4h → 4h, D-1).
 *
 * <p>이 정책 객체는 token 컨텍스트 {@link SafeModeManager}에만 주입되어 TTL·백오프 라이프사이클을 활성화한다(D-B 옵션 A). WebSocket
 * 컨텍스트 Bean에는 주입되지 않아(정책 {@code null}) 현행 TTL-less 동작이 보존된다(REQ-SAFEMODE-016).
 */
public final class SafeModeBackoffPolicy {

    private final Duration initialTtl;
    private final Duration maxTtl;

    public SafeModeBackoffPolicy(Duration initialTtl, Duration maxTtl) {
        this.initialTtl = initialTtl;
        this.maxTtl = maxTtl;
    }

    /**
     * 주어진 백오프 레벨에 대응하는 TTL을 계산한다.
     *
     * @param level 백오프 레벨(0부터 시작)
     * @return 산정된 TTL. {@code maxTtl}을 넘지 않는다
     */
    public Duration ttlForLevel(int level) {
        Duration candidate = initialTtl;
        for (int i = 0; i < level; i++) {
            candidate = candidate.multipliedBy(2);
            if (candidate.compareTo(maxTtl) >= 0) {
                return maxTtl;
            }
        }
        return candidate.compareTo(maxTtl) > 0 ? maxTtl : candidate;
    }
}
