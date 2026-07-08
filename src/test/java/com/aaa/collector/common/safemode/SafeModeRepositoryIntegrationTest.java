package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("SafeModeRepository 통합 테스트")
@Tag("integration")
class SafeModeRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String KEY_PREFIX = "safe_mode:collector:token:";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SafeModeRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        repository = new SafeModeRepository(redisTemplate, KEY_PREFIX);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("setSafeMode ON/OFF 전환 — isSafeMode가 각 상태를 올바르게 반환")
    void setSafeMode_onAndOff_isSafeModeReflectsState() {
        String alias = "safe-mode-alias";

        repository.setSafeMode(alias, true);
        assertThat(repository.isSafeMode(alias)).isTrue();

        repository.setSafeMode(alias, false);
        assertThat(repository.isSafeMode(alias)).isFalse();
    }

    @Test
    @DisplayName("초기 상태 — 키 미존재 시 isSafeMode가 false를 반환")
    void isSafeMode_whenKeyAbsent_returnsFalse() {
        assertThat(repository.isSafeMode("never-set-alias")).isFalse();
    }

    @Test
    @DisplayName("OFF → ON 전환 — setSafeMode(true) 후 isSafeMode가 true를 반환")
    void setSafeMode_offToOn_isSafeModeReturnsTrue() {
        String alias = "off-to-on-alias";
        repository.setSafeMode(alias, false);

        repository.setSafeMode(alias, true);

        assertThat(repository.isSafeMode(alias)).isTrue();
    }

    @Test
    @DisplayName("alias 간 격리 — alias A의 상태 변경이 alias B에 영향을 주지 않음")
    void setSafeMode_aliasA_doesNotAffectAliasB() {
        String aliasA = "alias-a";
        String aliasB = "alias-b";
        repository.setSafeMode(aliasB, false);

        repository.setSafeMode(aliasA, true);

        assertThat(repository.isSafeMode(aliasA)).isTrue();
        assertThat(repository.isSafeMode(aliasB)).isFalse();
    }

    // ── T-001: TTL 부여 (AC-1, D-D) ──────────────────────────────────────────

    @Test
    @DisplayName("setSafeMode(alias, true, ttl) — 저장된 \"ON\" 키에 TTL이 실제로 설정됨(getExpire > 0)")
    void setSafeMode_withTtl_actuallySetsRedisTtl() {
        String alias = "ttl-alias";

        repository.setSafeMode(alias, true, Duration.ofHours(1));

        Long expireSeconds = redisTemplate.getExpire(KEY_PREFIX + alias);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isPositive();
        assertThat(expireSeconds).isLessThanOrEqualTo(Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("setSafeMode(alias, true, ttl) 이후 키를 직접 삭제하면 TTL 만료를 시뮬레이션하여 isSafeMode가 false 반환")
    void setSafeMode_withTtl_thenKeyDeleted_simulatesExpiryAndReturnsFalse() {
        String alias = "ttl-expiry-alias";
        repository.setSafeMode(alias, true, Duration.ofHours(1));
        assertThat(repository.isSafeMode(alias)).isTrue();

        // D-D: 고정 sleep 대신 키를 직접 삭제해 TTL 만료를 시뮬레이션
        redisTemplate.delete(KEY_PREFIX + alias);

        assertThat(repository.isSafeMode(alias)).isFalse();
    }

    // ── T-001: 백오프 수준 지속 저장 (AC-2, D-A) ──────────────────────────────

    @Test
    @DisplayName("백오프 수준은 안전 모드 \"ON\" 키가 삭제(TTL 만료 시뮬레이션)된 이후에도 지속된다")
    void backoffLevel_persistsAfterSafeModeKeyDeleted() {
        String alias = "backoff-persist-alias";
        repository.setSafeMode(alias, true, Duration.ofHours(1));
        repository.saveBackoffLevel(alias, 1);

        redisTemplate.delete(KEY_PREFIX + alias);

        assertThat(repository.isSafeMode(alias)).isFalse();
        assertThat(repository.getBackoffLevel(alias)).contains(1);
    }

    @Test
    @DisplayName("deleteBackoffLevel 호출 후 getBackoffLevel은 Optional.empty()를 반환한다")
    void deleteBackoffLevel_thenGetBackoffLevel_returnsEmpty() {
        String alias = "backoff-delete-alias";
        repository.saveBackoffLevel(alias, 2);
        assertThat(repository.getBackoffLevel(alias)).contains(2);

        repository.deleteBackoffLevel(alias);

        assertThat(repository.getBackoffLevel(alias)).isEqualTo(Optional.empty());
    }
}
