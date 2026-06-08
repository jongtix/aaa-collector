package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SafeModeRepositoryTest {

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOps;

    private SafeModeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SafeModeRepository(redisTemplate, "safe_mode:collector:token:");
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName(
            "setSafeMode(true) — opsForValue().set(key, \"ON\") 호출, key 패턴이 {keyPrefix}{alias}")
    void setSafeMode_whenTrue_callsSetWithOnValue() {
        stubOpsForValue();
        String alias = "test-alias";

        repository.setSafeMode(alias, true);

        verify(valueOps).set("safe_mode:collector:token:" + alias, "ON");
    }

    @Test
    @DisplayName("setSafeMode(false) — opsForValue().set(key, \"OFF\") 호출")
    void setSafeMode_whenFalse_callsSetWithOffValue() {
        stubOpsForValue();
        String alias = "test-alias";

        repository.setSafeMode(alias, false);

        verify(valueOps).set("safe_mode:collector:token:" + alias, "OFF");
    }

    @Test
    @DisplayName("isSafeMode — \"ON\" 저장 시 true 반환")
    void isSafeMode_whenOn_returnsTrue() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("safe_mode:collector:token:" + alias)).thenReturn("ON");

        boolean result = repository.isSafeMode(alias);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("isSafeMode — \"OFF\" 저장 시 false 반환")
    void isSafeMode_whenOff_returnsFalse() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("safe_mode:collector:token:" + alias)).thenReturn("OFF");

        boolean result = repository.isSafeMode(alias);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("isSafeMode — 키 없음(null) 시 false 반환")
    void isSafeMode_whenKeyAbsent_returnsFalse() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("safe_mode:collector:token:" + alias)).thenReturn(null);

        boolean result = repository.isSafeMode(alias);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("ws 프리픽스 — 다른 keyPrefix를 사용하면 다른 키 패턴으로 저장된다")
    void setSafeMode_withWsPrefix_usesWsKeyPattern() {
        // Arrange
        SafeModeRepository wsRepository =
                new SafeModeRepository(redisTemplate, "safe_mode:collector:ws:");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        String alias = "test-alias";

        // Act
        wsRepository.setSafeMode(alias, true);

        // Assert
        verify(valueOps).set("safe_mode:collector:ws:" + alias, "ON");
    }
}
