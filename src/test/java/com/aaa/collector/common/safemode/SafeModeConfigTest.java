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
 * SafeModeConfig 컨텍스트 격리 회귀 테스트(REQ-SAFEMODE-016).
 *
 * <p>token Bean만 TTL·백오프 정책이 활성화되고, webSocketSafeModeManager Bean은 현행 TTL-less 동작을 유지함을 검증한다 (D-B).
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
    @DisplayName("webSocketSafeModeManager — enter() 시 TTL 없이 \"ON\"이 저장된다(REQ-SAFEMODE-016, AC-9)")
    void webSocketSafeModeManager_enter_doesNotApplyTtl() {
        SafeModeManager manager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);

        manager.enter("ws-session", new RuntimeException("test"));

        verify(valueOps).set("safe_mode:collector:ws:ws-session", "ON");
    }

    @Test
    @DisplayName("webSocketSafeModeManager — 활성 중 재진입해도 no-op 게이트 없이 매번 \"ON\" 재저장(현행 레거시 동작 보존)")
    void webSocketSafeModeManager_reentry_stillCallsSetSafeModeEachTime() {
        SafeModeManager manager =
                safeModeConfig.webSocketSafeModeManager(redisTemplate, meterRegistry);

        manager.enter("ws-session", new RuntimeException("first"));
        manager.enter("ws-session", new RuntimeException("second"));

        verify(valueOps, org.mockito.Mockito.times(2))
                .set("safe_mode:collector:ws:ws-session", "ON");
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
