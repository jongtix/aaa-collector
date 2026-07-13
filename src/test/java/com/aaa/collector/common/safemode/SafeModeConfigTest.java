package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * SafeModeConfig Bean 배선 회귀 테스트.
 *
 * <p>token·webSocketSafeModeManager 두 Bean 모두 TTL·백오프 정책이 활성화되어 있음을
 * 검증한다(SPEC-COLLECTOR-WS-RESILIENCE-001, REQ-WSRES-011~014 — SPEC-COLLECTOR-SAFEMODE-001
 * REQ-SAFEMODE-016 supersede).
 */
class SafeModeConfigTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MeterRegistry meterRegistry;
    private SafeModeConfig safeModeConfig;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        meterRegistry = new SimpleMeterRegistry();
        safeModeConfig = new SafeModeConfig();
    }

    @Test
    @DisplayName("tokenSafeModeManager — enter() 시 TTL(1h)이 부여된 \"ON\"이 저장된다(정책 활성)")
    void tokenSafeModeManager_enter_appliesInitialTtl() {
        SafeModeManager manager = safeModeConfig.tokenSafeModeManager(redisTemplate, meterRegistry);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        manager.enter("isa", new RuntimeException("test"));

        verify(valueOps).set("safe_mode:collector:token:isa", "ON", Duration.ofHours(1));
    }

    @Test
    @DisplayName(
            "webSocketSafeModeManager — enter() 시 TTL(1h)이 부여된 \"ON\"이 저장된다"
                    + "(SPEC-COLLECTOR-WS-RESILIENCE-001 REQ-WSRES-011, AC-10 — REQ-SAFEMODE-016 supersede)")
    void webSocketSafeModeManager_enter_appliesInitialTtl() {
        SafeModeManager manager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        manager.enter("ws-session", new RuntimeException("test"));

        verify(valueOps).set("safe_mode:collector:ws:ws-session", "ON", Duration.ofHours(1));
    }

    @Test
    @DisplayName(
            "webSocketSafeModeManager — 활성 중(TTL 미만료) 재진입은 no-op — setSafeMode 재호출 없음"
                    + "(REQ-WSRES-011 정책 활성 후 REQ-SAFEMODE-008 게이트 적용)")
    void webSocketSafeModeManager_reentryWhileActive_isNoOp() {
        SafeModeManager manager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);
        when(valueOps.get("safe_mode:collector:ws:ws-session")).thenReturn("ON");

        manager.enter("ws-session", new RuntimeException("first"));
        manager.enter("ws-session", new RuntimeException("second"));

        verify(valueOps, org.mockito.Mockito.never())
                .set(
                        org.mockito.ArgumentMatchers.eq("safe_mode:collector:ws:ws-session"),
                        org.mockito.ArgumentMatchers.eq("ON"),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName(
            "webSocketSafeModeManager — TTL 만료 후 재진입(백오프 레벨 0 존재) → TTL 2시간으로 확대"
                    + "(REQ-WSRES-013, AC-11)")
    void webSocketSafeModeManager_reentryAfterExpiry_expandsTo2h() {
        SafeModeManager manager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);
        // TTL 만료로 이미 비활성(isSafeMode=false, 기본 mock 반환값) — 백오프 레벨 0이 지속 저장된 상태를 시뮬레이션
        when(valueOps.get("safe_mode:collector:ws:backoff:ws-session")).thenReturn("0");

        manager.enter("ws-session", new RuntimeException("reentry"));

        verify(valueOps).set("safe_mode:collector:ws:ws-session", "ON", Duration.ofHours(2));
    }

    @Test
    @DisplayName("tokenSafeModeManager와 webSocketSafeModeManager는 서로 다른 키 프리픽스를 사용한다")
    void tokenAndWebSocketManagers_useDifferentKeyPrefixes() {
        SafeModeManager tokenManager =
                safeModeConfig.tokenSafeModeManager(redisTemplate, meterRegistry);
        SafeModeManager wsManager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);

        assertThat(tokenManager).isNotSameAs(wsManager);
    }
}
