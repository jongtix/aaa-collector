package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
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

    // ── T-001: TTL 부여 (REQ-SAFEMODE-001) ──────────────────────────────────

    @Test
    @DisplayName("setSafeMode(alias, true, ttl) — opsForValue().set(key, \"ON\", ttl) 호출")
    void setSafeMode_withTtl_callsSetWithOnValueAndTtl() {
        stubOpsForValue();
        String alias = "test-alias";
        Duration ttl = Duration.ofHours(1);

        repository.setSafeMode(alias, true, ttl);

        verify(valueOps).set("safe_mode:collector:token:" + alias, "ON", ttl);
    }

    @Test
    @DisplayName(
            "setSafeMode(alias, false, ttl) — ttl과 무관하게 opsForValue().set(key, \"OFF\") 호출(TTL 없음)")
    void setSafeMode_withTtlButOff_ignoresTtlAndCallsSetWithoutTtl() {
        stubOpsForValue();
        String alias = "test-alias";

        repository.setSafeMode(alias, false, Duration.ofHours(1));

        verify(valueOps).set("safe_mode:collector:token:" + alias, "OFF");
    }

    // ── T-001: 백오프 수준 지속 저장 (REQ-SAFEMODE-004, D-A) ─────────────────

    @Test
    @DisplayName("saveBackoffLevel — 별도 backoff 키에 레벨 문자열을 TTL 없이 저장")
    void saveBackoffLevel_callsSetOnBackoffKeyWithoutTtl() {
        stubOpsForValue();
        String alias = "test-alias";

        repository.saveBackoffLevel(alias, 1);

        verify(valueOps).set("safe_mode:collector:token:backoff:" + alias, "1");
    }

    @Test
    @DisplayName("getBackoffLevel — 저장된 값이 있으면 정수로 파싱하여 반환")
    void getBackoffLevel_whenPresent_returnsParsedInt() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("safe_mode:collector:token:backoff:" + alias)).thenReturn("2");

        Optional<Integer> result = repository.getBackoffLevel(alias);

        assertThat(result).contains(2);
    }

    @Test
    @DisplayName("getBackoffLevel — 키 없음(null) 시 Optional.empty() 반환")
    void getBackoffLevel_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("safe_mode:collector:token:backoff:" + alias)).thenReturn(null);

        Optional<Integer> result = repository.getBackoffLevel(alias);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteBackoffLevel — backoff 키 삭제 호출")
    void deleteBackoffLevel_callsDeleteOnBackoffKey() {
        String alias = "test-alias";

        repository.deleteBackoffLevel(alias);

        verify(redisTemplate).delete("safe_mode:collector:token:backoff:" + alias);
    }
}
