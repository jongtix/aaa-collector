package com.aaa.collector.common.safemode;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 안전 모드 Bean 설정.
 *
 * <p>토큰 컨텍스트와 WebSocket 컨텍스트에서 각각 독립적인 네임스페이스를 사용하는 {@link SafeModeManager} Bean을 등록한다.
 *
 * <p><b>컨텍스트 격리(SPEC-COLLECTOR-SAFEMODE-001, D-B 옵션 A) — SPEC-COLLECTOR-WS-RESILIENCE-001이
 * supersede</b>: 두 Bean 모두 {@link SafeModeBackoffPolicy}(초기 TTL 1시간, 상한 4시간)를 주입하여 TTL·재진입 백오프 자동
 * 복구 라이프사이클을 활성화한다. SPEC-COLLECTOR-SAFEMODE-001 REQ-SAFEMODE-016("WS는 확장 대상 아님")은
 * SPEC-COLLECTOR-WS-RESILIENCE-001 REQ-WSRES-011~014로 개정되었다 — WS 세이프모드가 영구 잠금(장애 이슈 aaa-infra#99)되는
 * 것을 방지하기 위해 TTL·백오프가 활성화된다.
 */
@Configuration
public class SafeModeConfig {

    private static final Duration TOKEN_INITIAL_TTL = Duration.ofHours(1);
    private static final Duration TOKEN_MAX_TTL = Duration.ofHours(4);

    /** WS 세이프모드 초기 TTL(REQ-WSRES-011) — 토큰 컨텍스트와 동일 정책 재사용. */
    private static final Duration WS_INITIAL_TTL = Duration.ofHours(1);

    /** WS 세이프모드 TTL 상한(REQ-WSRES-011). */
    private static final Duration WS_MAX_TTL = Duration.ofHours(4);

    /**
     * KIS 토큰 안전 모드 관리자. Redis 키: {@code safe_mode:collector:token:{alias}}
     *
     * <p>TTL·재진입 백오프(1h→2h→4h 상한) 정책이 활성화된 인스턴스다(SPEC-COLLECTOR-SAFEMODE-001).
     */
    @Bean
    public SafeModeManager tokenSafeModeManager(
            StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        return new SafeModeManager(
                new SafeModeRepository(redisTemplate, "safe_mode:collector:token:"),
                meterRegistry,
                "token",
                new SafeModeBackoffPolicy(TOKEN_INITIAL_TTL, TOKEN_MAX_TTL));
    }

    /**
     * KIS WebSocket 안전 모드 관리자. Redis 키: {@code safe_mode:collector:ws:{alias}}
     *
     * <p>TTL·재진입 백오프(1h→2h→4h 상한) 정책이 활성화된 인스턴스다(SPEC-COLLECTOR-WS-RESILIENCE-001,
     * REQ-WSRES-011~014). 최악의 경우(장 마감 직전 진입)에도 당일 밤 자동 해제되어 다음 개장에는 항상 깨끗한 상태로 시작한다.
     */
    @Bean
    public SafeModeManager webSocketSafeModeManager(
            StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        return new SafeModeManager(
                new SafeModeRepository(redisTemplate, "safe_mode:collector:ws:"),
                meterRegistry,
                "ws",
                new SafeModeBackoffPolicy(WS_INITIAL_TTL, WS_MAX_TTL));
    }
}
