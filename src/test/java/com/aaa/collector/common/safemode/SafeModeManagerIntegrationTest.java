package com.aaa.collector.common.safemode;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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

/**
 * SafeModeManager 통합 테스트 — token 컨텍스트 TTL·백오프 라이프사이클 전체 흐름(SPEC-COLLECTOR-SAFEMODE-001 D-D).
 *
 * <p>TTL 만료는 고정 sleep 대신 Redis "ON" 키를 직접 삭제해 시뮬레이션한다.
 */
@Testcontainers
@DisplayName("SafeModeManager 통합 테스트 (token 컨텍스트 TTL·백오프)")
@Tag("integration")
class SafeModeManagerIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String KEY_PREFIX = "safe_mode:collector:token:";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SafeModeManager manager;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SafeModeRepository repository = new SafeModeRepository(redisTemplate, KEY_PREFIX);
        SafeModeBackoffPolicy policy =
                new SafeModeBackoffPolicy(Duration.ofHours(1), Duration.ofHours(4));
        manager = new SafeModeManager(repository, new SimpleMeterRegistry(), "token", policy);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private void simulateTtlExpiry(String alias) {
        // D-D: 고정 sleep 대신 "ON" 키를 직접 삭제해 TTL 만료를 시뮬레이션. 백오프 레벨 키는 그대로 둔다.
        redisTemplate.delete(KEY_PREFIX + alias);
    }

    @Test
    @DisplayName("AC-1: 최초 진입 시 초기 TTL(1h)이 실제로 Redis에 설정된다(getExpire > 0)")
    void enter_firstEntry_setsRealRedisTtl() {
        String alias = "ac1-alias";

        manager.enter(alias, new RuntimeException("fail"));

        assertThat(manager.isActive(alias)).isTrue();
        Long expireSeconds = redisTemplate.getExpire(KEY_PREFIX + alias);
        assertThat(expireSeconds).isNotNull().isPositive();
        assertThat(expireSeconds).isLessThanOrEqualTo(Duration.ofHours(1).toSeconds());
    }

    @Test
    @DisplayName("AC-2/AC-10: 만료 → 재진입을 반복하면 TTL이 1h → 2h → 4h → 4h 순으로 확대되고 상한을 넘지 않는다")
    void enter_repeatedExpiryAndReentry_backoffExpands1h2h4h4h() {
        String alias = "ac2-alias";

        manager.enter(alias, new RuntimeException("first"));
        assertExpireApprox(alias, Duration.ofHours(1));

        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("second"));
        assertExpireApprox(alias, Duration.ofHours(2));

        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("third"));
        assertExpireApprox(alias, Duration.ofHours(4));

        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("fourth"));
        assertExpireApprox(alias, Duration.ofHours(4));
    }

    @Test
    @DisplayName("AC-2: 만료 이후에도 백오프 수준은 초기값으로 되돌아가지 않고 지속된다")
    void backoffLevel_persistsAcrossTtlExpiry() {
        String alias = "ac2-persist-alias";

        manager.enter(alias, new RuntimeException("first"));
        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("second"));

        // 두 번째 진입은 백오프 레벨 1(2h)이어야 한다 — 레벨이 리셋됐다면 다시 1h가 됐을 것
        assertExpireApprox(alias, Duration.ofHours(2));
    }

    @Test
    @DisplayName("AC-3: 백오프가 상승한 활성 키에 대해 resetBackoff+exit(발급 성공)을 수행하면 다음 진입은 다시 초기 TTL(1h)")
    void resetBackoffAndExit_afterSuccessOnActiveKey_nextEntryUsesInitialTtl() {
        String alias = "ac3-active-alias";
        manager.enter(alias, new RuntimeException("first"));
        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("second")); // 레벨 1, TTL 2h, 활성 상태

        // 발급 성공 시뮬레이션: resetBackoff 무조건 + 활성이므로 exit도 수행
        manager.resetBackoff(alias);
        manager.exit(alias);
        assertThat(manager.isActive(alias)).isFalse();

        manager.enter(alias, new RuntimeException("third"));
        assertExpireApprox(alias, Duration.ofHours(1));
    }

    @Test
    @DisplayName("AC-3 핵심 시나리오: TTL 자연만료로 이미 비활성인 키에 resetBackoff만 호출(exit 미호출)해도 백오프가 리셋된다")
    void resetBackoffOnly_afterTtlNaturalExpiry_resetsBackoffWithoutExit() {
        String alias = "ac3-inactive-alias";
        manager.enter(alias, new RuntimeException("first"));
        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("second")); // 레벨 1, TTL 2h

        // TTL 자연만료 시뮬레이션 → isActive=false, 백오프 레벨은 잔존
        simulateTtlExpiry(alias);
        assertThat(manager.isActive(alias)).isFalse();

        // exit() 호출 없이 resetBackoff만 호출(가장 흔한 회복 경로, D-F)
        manager.resetBackoff(alias);

        manager.enter(alias, new RuntimeException("third"));
        assertExpireApprox(alias, Duration.ofHours(1));
    }

    @Test
    @DisplayName("AC-8: 활성 중(TTL 미만료) 재진입은 no-op — 잔여 TTL과 백오프 수준이 그대로 유지된다")
    void enter_whileActive_reentryIsNoOp() {
        String alias = "ac8-alias";
        manager.enter(alias, new RuntimeException("first"));
        Long expireBefore = redisTemplate.getExpire(KEY_PREFIX + alias);

        // 활성 중 재진입(예: 방안 C 실패) — TTL 연장/단축/초기화 모두 없어야 함
        manager.enter(alias, new RuntimeException("reentry-while-active"));
        Long expireAfter = redisTemplate.getExpire(KEY_PREFIX + alias);

        assertThat(expireAfter).isNotNull();
        assertThat(expireBefore).isNotNull();
        // no-op이므로 잔여 TTL이 연장되지 않아야 한다(원래 TTL 이하로 유지)
        assertThat(expireAfter).isLessThanOrEqualTo(expireBefore);

        // 백오프 레벨도 그대로(다음 만료-후-재진입 시 여전히 2h가 되어야 함 = 레벨 0 유지 증거)
        simulateTtlExpiry(alias);
        manager.enter(alias, new RuntimeException("after-real-expiry"));
        assertExpireApprox(alias, Duration.ofHours(2));
    }

    private void assertExpireApprox(String alias, Duration expected) {
        Long expireSeconds = redisTemplate.getExpire(KEY_PREFIX + alias);
        assertThat(expireSeconds).isNotNull();
        assertThat(expireSeconds).isPositive();
        assertThat(expireSeconds).isLessThanOrEqualTo(expected.toSeconds());
        // 여유를 두고 최소 절반 이상은 남아있어야 함(즉시 실행된 테스트이므로)
        assertThat(expireSeconds).isGreaterThan(expected.toSeconds() / 2);
    }
}
