package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("SafeModeRepository 통합 테스트")
class SafeModeRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private SafeModeRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        repository = new SafeModeRepository(redisTemplate);
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
}
