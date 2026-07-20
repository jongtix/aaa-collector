package com.aaa.collector.kis.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aaa.collector.common.safemode.SafeModeBackoffPolicy;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.common.safemode.SafeModeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
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
 * SafeModeGaugeBinder 통합 테스트 — 실제 Redis(Testcontainers) 기반으로 이슈#85 핵심 결함 경로를 고정한다
 * (SPEC-COLLECTOR-SAFEMODEGAUGE-001 REQ-SMG-008 · AC-8).
 *
 * <p><b>핵심 시나리오</b>: 짧은 실제 TTL로 안전 모드에 진입한 뒤 <b>어떤 {@code exit()} 호출도 없이</b> TTL이 <b>자연 만료</b>되면
 * 게이지가 {@code 0}으로 떨어진다. 기존 {@code exit_total} 카운터로는 절대 포착하지 못하던 경로다. 이 테스트는 만료를 고정 sleep이나 키 수동
 * 삭제로 시뮬레이션하지 않고, Awaitility 폴링으로 Redis TTL의 <b>진짜 자연 만료</b>를 대기해 결함을 실측 고정한다.
 */
@Testcontainers
@DisplayName("SafeModeGaugeBinder 통합 테스트 (TTL 자연 만료 → 게이지 0)")
@Tag("integration")
class SafeModeGaugeBinderIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String GAUGE = "aaa_collector_safe_mode_active";
    private static final String TOKEN_KEY_PREFIX = "safe_mode:collector:token:";
    private static final String WS_KEY_PREFIX = "safe_mode:collector:ws:";
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry registry;
    private SafeModeManager tokenManager;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        registry = new SimpleMeterRegistry();

        // 짧은 실제 TTL(2s) 정책으로 token 컨텍스트 매니저 구성 — enter() 시 Redis 키에 실제 만료가 걸린다
        SafeModeBackoffPolicy shortPolicy = new SafeModeBackoffPolicy(SHORT_TTL, SHORT_TTL);
        tokenManager =
                new SafeModeManager(
                        new SafeModeRepository(redisTemplate, TOKEN_KEY_PREFIX),
                        registry,
                        "token",
                        shortPolicy);
        // ws 컨텍스트도 함께 등록해 (module, alias) 전 조합 게이지가 생성되도록 한다(실제 사용 형태 재현)
        SafeModeManager webSocketManager =
                new SafeModeManager(
                        new SafeModeRepository(redisTemplate, WS_KEY_PREFIX),
                        registry,
                        "ws",
                        new SafeModeBackoffPolicy(SHORT_TTL, SHORT_TTL));

        KisProperties kisProperties =
                new KisProperties(
                        "https://openapi.koreainvestment.com",
                        "testuser",
                        List.of(
                                new KisAccountCredential(
                                        "isa", "12345678", "appkey-isa", "appsecret-isa")),
                        new KisProperties.RateLimit(20, 20, 5));

        SafeModeGaugeBinder binder =
                new SafeModeGaugeBinder(tokenManager, webSocketManager, kisProperties, registry);
        binder.registerGauges();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private double gaugeValue(String module, String alias) {
        return registry.get(GAUGE).tags("module", module, "alias", alias).gauge().value();
    }

    @Test
    @DisplayName("AC-8: exit() 호출 없이 TTL 자연 만료 시 게이지가 0으로 떨어진다 (이슈#85 핵심 결함 경로)")
    void gaugeDropsToZeroOnNaturalTtlExpiryWithoutExit() {
        String alias = "isa";

        // Arrange: 짧은 실제 TTL로 안전 모드 진입 — exit()은 호출하지 않는다
        tokenManager.enter(alias, new RuntimeException("token 발급 실패"));
        assertThat(gaugeValue("token", alias)).isEqualTo(1.0);
        // 실제 TTL이 Redis 키에 걸려 있음을 확인(수동 삭제/시뮬레이션이 아닌 진짜 만료 경로)
        assertThat(redisTemplate.getExpire(TOKEN_KEY_PREFIX + alias)).isPositive();

        // Act & Assert: 어떤 exit()도 호출하지 않고 TTL 자연 만료를 Awaitility 폴링으로 대기한다
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(gaugeValue("token", alias)).isEqualTo(0.0));

        // 게이지가 0인 이유가 자연 만료(키 소멸)임을 확인 — getExpire == -2(키 없음). 수동 삭제를 하지 않았다.
        assertThat(redisTemplate.getExpire(TOKEN_KEY_PREFIX + alias)).isEqualTo(-2L);
    }
}
