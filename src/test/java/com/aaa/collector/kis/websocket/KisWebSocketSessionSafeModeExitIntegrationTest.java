package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.safemode.SafeModeBackoffPolicy;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.common.safemode.SafeModeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * REQ-WSEXIT-004 통합 검증 — 재연결 성공 훅포인트(hookpoint 2)의 exit(alias) 호출이 실제 Redis 상태를 즉시 되돌리는지 검증한다
 * (SPEC-COLLECTOR-WS-SAFEMODE-EXIT-001).
 *
 * <p>{@link SafeModeManagerIntegrationTest}와 동일한 실제 Redis Testcontainers 패턴을 사용하되, WS 세이프모드 TTL을
 * 2초로 단축한 전용 {@link SafeModeBackoffPolicy}를 주입한다. TTL이 만료되기 전에 재연결 성공 경로(hookpoint 2)를 동기적으로 구동하여,
 * 관측된 해제가 능동적 {@code exit()} 호출에 의한 것이지 TTL 자연 만료에 의한 것이 아님을 시간창(2초) 안에서 입증한다.
 */
@Testcontainers
@DisplayName("KisWebSocketSession 재연결 성공 → SafeMode exit() 통합 검증 (REQ-WSEXIT-004)")
@Tag("integration")
class KisWebSocketSessionSafeModeExitIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String KEY_PREFIX = "safe_mode:collector:ws:";
    private static final String ALIAS = "ws-safemode-exit-it-alias";
    private static final String APPROVAL_KEY = "test-approval-key";
    private static final String WS_URL = "ws://ops.koreainvestment.com:21000";

    /** TTL 만료가 아닌 능동적 exit() 호출임을 입증하기 위한 짧은 TTL — 만료 전에 검증을 완료해야 한다. */
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SafeModeManager webSocketSafeModeManager;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SafeModeRepository repository = new SafeModeRepository(redisTemplate, KEY_PREFIX);
        SafeModeBackoffPolicy shortTtlPolicy = new SafeModeBackoffPolicy(SHORT_TTL, SHORT_TTL);
        webSocketSafeModeManager =
                new SafeModeManager(repository, new SimpleMeterRegistry(), "ws", shortTtlPolicy);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("2초 TTL로 세이프모드 진입 후, TTL 만료 전 재연결 성공(hookpoint 2)을 구동하면 즉시 해제된다(exit() 귀속 증명)")
    void reconnectSuccess_beforeTtlExpiry_immediatelyClearsSafeMode() throws Exception {
        // Arrange — 2초 TTL로 세이프모드 진입
        webSocketSafeModeManager.enter(ALIAS, new RuntimeException("재연결 5회 연속 실패(시뮬레이션)"));
        assertThat(webSocketSafeModeManager.isActive(ALIAS)).isTrue();
        Long expireSeconds = redisTemplate.getExpire(KEY_PREFIX + ALIAS);
        assertThat(expireSeconds).isNotNull().isPositive();

        WebSocketClient webSocketClient = mock(WebSocketClient.class);
        KisWebSocketMessageHandler messageHandler = mock(KisWebSocketMessageHandler.class);
        KisMarketSchedule marketSchedule = mock(KisMarketSchedule.class);
        // 모의 WebSocketSession은 try-with-resources로 닫는다(mock close()는 무동작) — PMD CloseResource 준수.
        try (WebSocketSession rawSession = mock(WebSocketSession.class)) {
            @SuppressWarnings("unchecked")
            CompletableFuture<WebSocketSession> handshakeFuture = mock(CompletableFuture.class);

            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);
            when(handshakeFuture.get()).thenReturn(rawSession);
            when(rawSession.isOpen()).thenReturn(true);
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);

            // 재연결 대기(sleep)를 즉시 통과시켜 2초 TTL 창 안에서 검증을 완료한다 — 실제 Thread.sleep 대신 no-op.
            Clock fixedClock = Clock.fixed(Instant.now(), ZoneId.of("Asia/Seoul"));
            KisWebSocketSession session =
                    new KisWebSocketSession(
                            ALIAS,
                            APPROVAL_KEY,
                            webSocketClient,
                            messageHandler,
                            marketSchedule,
                            webSocketSafeModeManager,
                            millis -> {}, // Sleeper no-op — 지수 백오프 대기를 생략해 TTL 창 안에서 동기 검증
                            fixedClock);
            session.connect(WS_URL); // 최초 연결(setUp에서의 handshake 1회 소비)

            ZonedDateTime marketOpen = ZonedDateTime.now(fixedClock);

            // Act — 재연결 성공 경로(hookpoint 2)를 동기적으로 구동
            session.handleDisconnect(marketOpen);

            // Assert — TTL(2초) 만료 전에 즉시 확인. isActive()=false + Redis 키 자체가 부재(DEL, REQ-WSEXIT-007과의
            // 조합 검증)해야 exit()에 의한 능동 해제로 귀속할 수 있다(TTL 자연 만료라면 이 시점엔 아직 키가 남아있어야 함).
            assertThat(webSocketSafeModeManager.isActive(ALIAS)).isFalse();
            assertThat(redisTemplate.hasKey(KEY_PREFIX + ALIAS)).isFalse();
        }
    }
}
