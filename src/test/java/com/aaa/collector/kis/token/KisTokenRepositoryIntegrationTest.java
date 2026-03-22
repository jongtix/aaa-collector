package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
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
@DisplayName("KisTokenRepository 통합 테스트")
class KisTokenRepositoryIntegrationTest {

    private static final Duration SHORT_TTL = Duration.ofSeconds(1);
    private static final long TTL_EXPIRY_BUFFER_MS = 200L;

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private KisTokenRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        repository = new KisTokenRepository(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("saveToken 후 findToken — 저장한 토큰 조회 성공")
    void saveToken_thenFindToken_returnsStoredToken() {
        String alias = "test-alias";
        String token = "access-token-value";
        Duration ttl = Duration.ofMinutes(30);

        repository.saveToken(alias, token, ttl);
        Optional<String> result = repository.findToken(alias);

        assertThat(result).contains(token);
    }

    @Test
    @DisplayName("TTL 만료 후 findToken — Optional.empty() 반환")
    @SuppressWarnings("java:S2925") // Redis TTL 만료를 실제로 기다려야 하는 통합 테스트 — 대체 수단 없음
    void findToken_afterTtlExpiry_returnsEmpty() throws InterruptedException {
        String alias = "ttl-alias";
        String token = "expiring-token";

        repository.saveToken(alias, token, SHORT_TTL);
        Thread.sleep(SHORT_TTL.toMillis() + TTL_EXPIRY_BUFFER_MS);
        Optional<String> result = repository.findToken(alias);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteToken 후 findToken — Optional.empty() 반환")
    void deleteToken_thenFindToken_returnsEmpty() {
        String alias = "delete-alias";
        String token = "token-to-delete";
        repository.saveToken(alias, token, Duration.ofMinutes(10));

        repository.deleteToken(alias);
        Optional<String> result = repository.findToken(alias);

        assertThat(result).isEmpty();
    }
}
