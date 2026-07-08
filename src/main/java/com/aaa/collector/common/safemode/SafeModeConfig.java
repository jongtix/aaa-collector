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
 * <p><b>컨텍스트 격리(SPEC-COLLECTOR-SAFEMODE-001, D-B 옵션 A, REQ-SAFEMODE-016)</b>: {@code
 * tokenSafeModeManager}에만 {@link SafeModeBackoffPolicy}(초기 TTL 1시간, 상한 4시간)를 주입하여 TTL·재진입 백오프 자동 복구
 * 라이프사이클을 활성화한다. {@code webSocketSafeModeManager}는 정책을 주입하지 않아(레거시 3-args 생성자) 현행 TTL-less·백오프-없음
 * 동작을 그대로 유지한다.
 */
@Configuration
public class SafeModeConfig {

    private static final Duration TOKEN_INITIAL_TTL = Duration.ofHours(1);
    private static final Duration TOKEN_MAX_TTL = Duration.ofHours(4);

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
     * <p>TTL·백오프 정책을 주입하지 않아 현행 TTL 없는 영구 저장 동작을 유지한다(REQ-SAFEMODE-016 — 본 SPEC의 확장 대상 아님).
     */
    @Bean
    public SafeModeManager webSocketSafeModeManager(
            StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        return new SafeModeManager(
                new SafeModeRepository(redisTemplate, "safe_mode:collector:ws:"),
                meterRegistry,
                "ws");
    }
}
