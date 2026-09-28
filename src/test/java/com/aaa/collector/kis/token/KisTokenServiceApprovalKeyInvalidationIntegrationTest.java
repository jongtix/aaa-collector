package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.safemode.SafeModeManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
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
 * approval_key 무효화 후 재요청 경로를 실제 Redis로 검증한다(REQ-WSRES2-007, acceptance.md AC-5 통합 검증).
 *
 * <p>{@link KisTokenService#invalidateApprovalKey}가 실제 Redis 캐시를 비우고, 이어지는 {@link
 * KisTokenService#getValidApprovalKey}가 캐시 미스로 {@link KisTokenClient#requestApprovalKey}를 태워 새 키를
 * 저장하는 전 과정을 확인한다. approval_key TTL은 24시간 고정이라 벽시계·sleep에 의존하지 않는다.
 */
@Testcontainers
@DisplayName("KisTokenService approval_key 무효화 후 재요청 통합 검증 (AC-5)")
@Tag("integration")
class KisTokenServiceApprovalKeyInvalidationIntegrationTest {

    private static final String ALIAS = "approval-invalidate-it-alias";

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private KisTokenRepository repository;
    private KisTokenClient kisTokenClient;
    private KisAccountCredential credential;
    private KisTokenService kisTokenService;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        repository = new KisTokenRepository(redisTemplate);
        repository.deleteApprovalKey(ALIAS); // 정적 컨테이너 공유 — 테스트 간 상태 격리

        kisTokenClient = mock(KisTokenClient.class);
        credential = new KisAccountCredential(ALIAS, "12345678", "test-app-key", "test-app-secret");
        KisProperties kisProperties =
                new KisProperties(
                        "https://localhost",
                        "testUser",
                        List.of(credential),
                        new KisProperties.RateLimit(20, 20, 10));
        Clock fixedClock =
                Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneId.of("Asia/Seoul"));
        kisTokenService =
                new KisTokenService(
                        kisProperties,
                        kisTokenClient,
                        repository,
                        mock(SafeModeManager.class),
                        millis -> {},
                        fixedClock,
                        key -> new ReentrantLock());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName(
            "AC-5: invalidateApprovalKey는 실제 Redis에 캐시된 approval_key를 지워 findApprovalKey가 빈 값이 된다")
    void invalidateApprovalKey_removesCachedKeyFromRedis() {
        // Arrange
        repository.saveApprovalKey(ALIAS, "stale-key");
        assertThat(repository.findApprovalKey(ALIAS)).contains("stale-key");

        // Act
        kisTokenService.invalidateApprovalKey(ALIAS);

        // Assert
        assertThat(repository.findApprovalKey(ALIAS)).isEmpty();
    }

    @Test
    @DisplayName(
            "AC-5: 무효화 후 getValidApprovalKey는 캐시 미스로 requestApprovalKey를 태워 새 키를 발급·저장하고,"
                    + " 이후 조회는 캐시 히트다")
    void getValidApprovalKey_afterInvalidation_reRequestsFromKisThenServesFromCache() {
        // Arrange
        repository.saveApprovalKey(ALIAS, "stale-key");
        kisTokenService.invalidateApprovalKey(ALIAS);
        when(kisTokenClient.requestApprovalKey(credential))
                .thenReturn(new KisApprovalKeyResponse("fresh-key"));

        // Act
        String reissued = kisTokenService.getValidApprovalKey(ALIAS);
        String secondLookup = kisTokenService.getValidApprovalKey(ALIAS);

        // Assert — KIS 재요청은 첫 조회에서만 1회, 새 키가 Redis에 저장되어 두 번째 조회는 캐시에서 나온다
        assertThat(reissued).isEqualTo("fresh-key");
        assertThat(repository.findApprovalKey(ALIAS)).contains("fresh-key");
        assertThat(secondLookup).isEqualTo("fresh-key");
        verify(kisTokenClient, times(1)).requestApprovalKey(credential);
    }
}
