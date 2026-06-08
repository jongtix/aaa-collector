package com.aaa.collector.kis.token;

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
class KisTokenRepositoryTest {

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOps;

    private KisTokenRepository repository;

    @BeforeEach
    void setUp() {
        repository = new KisTokenRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName(
            "saveToken — opsForValue().set(key, token, ttl) 호출, key 패턴이 cache:kis:token:{alias}")
    void saveToken_callsSetWithKeyTokenAndTtl() {
        stubOpsForValue();
        String alias = "test-alias";
        String token = "access-token-value";
        Duration ttl = Duration.ofHours(1);

        repository.saveToken(alias, token, ttl);

        verify(valueOps).set("cache:kis:token:" + alias, token, ttl);
    }

    @Test
    @DisplayName("findToken — 값이 존재하면 Optional.of(token) 반환")
    void findToken_whenExists_returnsOptionalOfToken() {
        stubOpsForValue();
        String alias = "test-alias";
        String token = "access-token-value";
        when(valueOps.get("cache:kis:token:" + alias)).thenReturn(token);

        Optional<String> result = repository.findToken(alias);

        assertThat(result).contains(token);
    }

    @Test
    @DisplayName("findToken — 값이 없으면 Optional.empty() 반환")
    void findToken_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("cache:kis:token:" + alias)).thenReturn(null);

        Optional<String> result = repository.findToken(alias);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteToken — delete(key) 호출")
    void deleteToken_callsDeleteWithKey() {
        String alias = "test-alias";

        repository.deleteToken(alias);

        verify(redisTemplate).delete("cache:kis:token:" + alias);
    }

    @Test
    @DisplayName(
            "saveApprovalKey — opsForValue().set(key, approvalKey, 1일 TTL) 호출, key 패턴이 cache:kis:approval_key:{alias}")
    void saveApprovalKey_callsSetWithKeyApprovalKeyAnd1DayTtl() {
        stubOpsForValue();
        String alias = "test-alias";
        String approvalKey = "approval-key-value";

        repository.saveApprovalKey(alias, approvalKey);

        verify(valueOps).set("cache:kis:approval_key:" + alias, approvalKey, Duration.ofDays(1));
    }

    @Test
    @DisplayName("findApprovalKey — 값이 존재하면 Optional.of(approvalKey) 반환")
    void findApprovalKey_whenExists_returnsOptionalOfApprovalKey() {
        stubOpsForValue();
        String alias = "test-alias";
        String approvalKey = "approval-key-value";
        when(valueOps.get("cache:kis:approval_key:" + alias)).thenReturn(approvalKey);

        Optional<String> result = repository.findApprovalKey(alias);

        assertThat(result).contains(approvalKey);
    }

    @Test
    @DisplayName("findApprovalKey — 값이 없으면 Optional.empty() 반환")
    void findApprovalKey_whenAbsent_returnsEmpty() {
        stubOpsForValue();
        String alias = "test-alias";
        when(valueOps.get("cache:kis:approval_key:" + alias)).thenReturn(null);

        Optional<String> result = repository.findApprovalKey(alias);

        assertThat(result).isEmpty();
    }
}
