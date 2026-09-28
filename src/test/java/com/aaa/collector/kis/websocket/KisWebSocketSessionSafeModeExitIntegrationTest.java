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
 * REQ-WSRES2-008 통합 검증(REQ-WSEXIT-004 개정) — 재연결 성공 훅포인트(hookpoint 2)만으로는 더 이상 안전모드를 능동 해제하지 않음을 실제
 * Redis 상태로 검증한다 (SPEC-COLLECTOR-WS-RESILIENCE-002, 원 동작은 SPEC-COLLECTOR-WS-SAFEMODE-EXIT-001
 * REQ-WSEXIT-004 "지점 2").
 *
 * <p>2026-09-28 인시던트에서 이 지점 2의 조기 해제가 approval_key 인증 실패와 결합해 무한 재연결 루프를 유발했다(spec.md §1 결함②).
 * {@link SafeModeManagerIntegrationTest}와 동일한 실제 Redis Testcontainers 패턴을 사용하되, WS 세이프모드 TTL을 2초로
 * 단축한 전용 {@link SafeModeBackoffPolicy}를 주입한다 — 재연결 성공 경로(hookpoint 2)를 TTL 만료 전에 동기적으로 구동해도 안전모드가
 * 여전히 활성 상태(Redis 키 존재)로 남아있음을 확인함으로써, 관측된 유지가 TTL 자연 만료 유예 때문이 아니라 hookpoint 2의 exit() 호출 자체가
 * 제거되었기 때문임을 입증한다.
 */
@Testcontainers
@DisplayName("KisWebSocketSession 재연결 성공만으로는 SafeMode 미해제 (REQ-WSRES2-008, REQ-WSEXIT-004 개정)")
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
    @DisplayName(
            "REQ-WSRES2-008 재현-우선(반전): 2초 TTL로 세이프모드 진입 후, TTL 만료 전 재연결 성공(hookpoint 2)만"
                    + " 구동해도 세이프모드가 해제되지 않는다(exit() 미호출 귀속 증명) — 개정 전 이 테스트는 즉시 해제를"
                    + " 단언했다")
    void reconnectSuccess_beforeTtlExpiry_doesNotClearSafeModeWithoutSubscriptionConfirmation()
            throws Exception {
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

            // Act — 재연결 성공 경로(hookpoint 2)를 동기적으로 구동 (구독 성공 응답은 시뮬레이션하지 않음)
            session.handleDisconnect(marketOpen);

            // Assert — TTL(2초) 만료 전에 즉시 확인. isActive()=true + Redis 키가 여전히 존재해야
            // REQ-WSRES2-008(hookpoint 2 exit() 제거)이 올바르게 적용되었음을 입증할 수 있다 — 만약 exit()가
            // 여전히 호출된다면 이 시점에 이미 키가 삭제되어 있을 것이다.
            assertThat(webSocketSafeModeManager.isActive(ALIAS)).isTrue();
            assertThat(redisTemplate.hasKey(KEY_PREFIX + ALIAS)).isTrue();
        }
    }
}
