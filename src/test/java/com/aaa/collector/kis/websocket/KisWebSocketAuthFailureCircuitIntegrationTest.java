package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 인증 실패 회로차단기(REQ-WSRES2-009/010)와 기존 WS 세이프모드 TTL·백오프(REQ-WSRES-011~013)의 독립성을 실제 Redis로
 * 검증한다(SPEC-COLLECTOR-WS-RESILIENCE-002, acceptance.md 엣지 케이스 E3).
 *
 * <p>{@link KisWebSocketSessionSafeModeExitIntegrationTest}와 동일한 실제 Redis Testcontainers 패턴을 사용하되,
 * WS 세이프모드 TTL을 1초로 단축한 전용 {@link SafeModeBackoffPolicy}를 주입한다. 2026-09-28 인시던트를 축소 재현 — 동일 무효
 * approval_key로 인한 구독 실패를 반복 주입해 (1) REQ-WS-016 임계값(5회 연속)으로 세이프모드가 진입하고, (2) TTL 만료로 자동 해제된 뒤, (3)
 * 추가 실패로 재진입(REQ-WSRES-013 백오프 레벨 증가)하는 전 과정을 실제로 구동한 다음, 이 세이프모드 상태 전환과 무관하게 회로차단기(재연결 지연 하한 강제)가
 * 계속 발동 상태로 유지됨을 확인한다.
 */
@Testcontainers
@DisplayName("인증 실패 회로차단기와 WS 세이프모드 TTL·백오프의 독립성 통합 검증 (AC-7/AC-8, 엣지 케이스 E3)")
@Tag("integration")
class KisWebSocketAuthFailureCircuitIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final String KEY_PREFIX = "safe_mode:collector:ws:";
    private static final String ALIAS = "auth-circuit-it-alias";
    private static final String APPROVAL_KEY = "test-approval-key";
    private static final String WS_URL = "ws://ops.koreainvestment.com:21000";
    private static final String INVALID_APPROVAL_KEY_VALUE = "32b39a3b-467d-4266-bf35-7709892eff52";

    /** TTL 자연 만료+재진입을 테스트 실행 시간 내에서 실증하기 위한 짧은 TTL. */
    private static final Duration SHORT_TTL = Duration.ofSeconds(1);

    /**
     * AC-7/AC-8 전용 세이프모드 TTL — 시간은 주입한 Clock/Sleeper로만 흐르므로 실제 Redis TTL이 테스트 도중 만료되면 안 된다. 느린 CI
     * 머신에서도 경쟁이 없도록 충분히 길게 잡는다(벽시계 60초 이상).
     */
    private static final Duration LONG_TTL = Duration.ofHours(1);

    /** 회로차단기(REQ-WSRES2-009) 재연결 지연 하한(밀리초) — {@code KisWebSocketSession}의 값과 같아야 한다. */
    private static final long CIRCUIT_COOLDOWN_MS = 30_000L;

    /** AC-7 가상 시간 관측 창(초). */
    private static final long BOUND_WINDOW_SECONDS = 300L;

    private LettuceConnectionFactory connectionFactory;
    private SafeModeManager webSocketSafeModeManager;
    private SafeModeManager longTtlSafeModeManager;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        SafeModeRepository repository = new SafeModeRepository(redisTemplate, KEY_PREFIX);
        SafeModeBackoffPolicy shortTtlPolicy = new SafeModeBackoffPolicy(SHORT_TTL, SHORT_TTL);
        webSocketSafeModeManager =
                new SafeModeManager(repository, new SimpleMeterRegistry(), "ws", shortTtlPolicy);
        longTtlSafeModeManager =
                new SafeModeManager(
                        repository,
                        new SimpleMeterRegistry(),
                        "ws",
                        new SafeModeBackoffPolicy(LONG_TTL, LONG_TTL.multipliedBy(4)));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /** 2026-09-28 인시던트 실측(VictoriaLogs) — 동일 무효 approval_key로 반복되는 구독 실패 응답. */
    private static String authFailureJson() {
        return """
                {
                  "header": {"tr_id": "H0STASP0", "tr_key": "005930"},
                  "body": {
                    "rt_cd": "1",
                    "msg1": "invalid approval : %s"
                  }
                }
                """
                .replace("%s", INVALID_APPROVAL_KEY_VALUE);
    }

    @Test
    @DisplayName(
            "E3: 세이프모드가 TTL 만료로 자동 해제된 뒤 재진입(REQ-WSRES-013)을 거쳐도, 회로차단기(REQ-WSRES2-009)의"
                    + " 재연결 지연 강제는 리셋되지 않고 유지된다")
    void authFailureCircuit_remainsIndependentOfSafeModeTtlExpiryAndReentry() throws Exception {
        // Arrange
        List<Long> recordedDelays = new CopyOnWriteArrayList<>();
        Sleeper recordingSleeper = recordedDelays::add;
        Clock clock = Clock.systemDefaultZone();

        KisWebSocketMessageHandler handler =
                new KisWebSocketMessageHandler(
                        ALIAS, mock(KisTickPublisher.class), webSocketSafeModeManager, clock);
        WebSocketClient webSocketClient = mock(WebSocketClient.class);
        KisMarketSchedule marketSchedule = mock(KisMarketSchedule.class);
        when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
        when(marketSchedule.isOverseasOpen(any())).thenReturn(false);

        try (WebSocketSession rawSession = mock(WebSocketSession.class)) {
            when(rawSession.isOpen()).thenReturn(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<WebSocketSession> handshakeFuture = mock(CompletableFuture.class);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);
            when(handshakeFuture.get()).thenReturn(rawSession);

            KisWebSocketSession session =
                    new KisWebSocketSession(
                            ALIAS,
                            APPROVAL_KEY,
                            webSocketClient,
                            handler,
                            marketSchedule,
                            webSocketSafeModeManager,
                            recordingSleeper,
                            clock);
            handler.setAuthFailureCallback(session::recordAuthFailure);
            handler.setApprovalKeyReissueCallback(() -> {});
            session.connect(WS_URL);

            // Act 1 — 인증 실패 5회 주입(REQ-WS-016 임계값) → 세이프모드 진입 + 회로차단기 판정 창 갱신(REQ-WSRES2-009)
            TextMessage authFailureMessage = new TextMessage(authFailureJson());
            for (int i = 0; i < 5; i++) {
                handler.handleTextMessage(rawSession, authFailureMessage);
            }
            assertThat(webSocketSafeModeManager.isActive(ALIAS)).isTrue();

            // Act 2 — TTL(1초) 자연 만료 대기(REQ-WSRES-012)
            Thread.sleep(1200);
            assertThat(webSocketSafeModeManager.isActive(ALIAS)).isFalse();

            // Act 3 — 만료 후 추가 인증 실패 주입 → 재진입(REQ-WSRES-013 백오프 레벨 증가) + 회로차단기 창 계속 누적
            handler.handleTextMessage(rawSession, authFailureMessage);
            assertThat(webSocketSafeModeManager.isActive(ALIAS)).isTrue();

            // Act 4 — 재연결 시도 → 회로차단기 판정(창 내 인증 실패 누적 6회, 임계값 3회 초과)
            ZonedDateTime marketOpen = ZonedDateTime.now(clock);
            session.handleDisconnect(marketOpen);

            // Assert — 세이프모드가 TTL 만료→재진입을 거쳤음에도 회로차단기는 리셋되지 않고 여전히 발동 상태(E3) —
            // 재연결 지연이 회로차단기 하한(30000ms) 이상으로 강제되어야 한다
            assertThat(recordedDelays).isNotEmpty();
            assertThat(recordedDelays.get(recordedDelays.size() - 1))
                    .isGreaterThanOrEqualTo(30_000L);
        }
    }

    @Test
    @DisplayName("AC-7: 인증 실패가 반복되는 300초(가상 시간) 동안 재연결 시도 횟수는 회로차단기 하한으로 정해진 상한을 넘지 않는다")
    void repeatedAuthFailures_boundReconnectAttemptsWithinVirtualWindow() throws Exception {
        // Arrange — 시간은 Sleeper가 가상 시계를 전진시키는 방식으로만 흐른다(실제 sleep·벽시계 단언 없음)
        String alias = "auth-circuit-it-ac7-alias";
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-09-28T01:00:00Z"));
        List<Long> recordedDelays = new CopyOnWriteArrayList<>();
        Sleeper virtualTimeSleeper =
                millis -> {
                    recordedDelays.add(millis);
                    clock.advance(Duration.ofMillis(millis));
                };
        KisWebSocketMessageHandler handler =
                new KisWebSocketMessageHandler(
                        alias, mock(KisTickPublisher.class), longTtlSafeModeManager, clock);
        WebSocketClient webSocketClient = mock(WebSocketClient.class);
        KisMarketSchedule marketSchedule = mock(KisMarketSchedule.class);
        when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
        when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
        AtomicInteger executeCalls = new AtomicInteger();

        try (WebSocketSession rawSession = mock(WebSocketSession.class)) {
            when(rawSession.isOpen()).thenReturn(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<WebSocketSession> handshakeFuture = mock(CompletableFuture.class);
            when(handshakeFuture.get()).thenReturn(rawSession);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenAnswer(
                            invocation -> {
                                executeCalls.incrementAndGet();
                                return handshakeFuture;
                            });

            KisWebSocketSession session =
                    new KisWebSocketSession(
                            alias,
                            APPROVAL_KEY,
                            webSocketClient,
                            handler,
                            marketSchedule,
                            longTtlSafeModeManager,
                            virtualTimeSleeper,
                            clock);
            handler.setAuthFailureCallback(session::recordAuthFailure);
            handler.setApprovalKeyReissueCallback(() -> {});
            session.connect(WS_URL);
            executeCalls.set(0); // 초기 접속은 "재연결 시도"가 아니므로 제외

            // Act — 재연결 직후 재구독이 거절되고(5건) 서버가 소켓을 끊는 사이클을 창이 끝날 때까지 반복
            Instant windowEnd = clock.instant().plusSeconds(BOUND_WINDOW_SECONDS);
            TextMessage authFailureMessage = new TextMessage(authFailureJson());
            int iterations = 0;
            while (clock.instant().isBefore(windowEnd) && iterations < 1_000) {
                for (int i = 0; i < 5; i++) {
                    handler.handleTextMessage(rawSession, authFailureMessage);
                }
                session.handleDisconnect(ZonedDateTime.ofInstant(clock.instant(), clock.getZone()));
                iterations++;
            }

            // Assert — 사이클마다 지연 하한(30초)이 강제되므로 300초 창 안의 재연결 시도는 창/하한 + 1 이하.
            // 하한이 없으면(사이클당 1초 백오프) 같은 창에서 수백 회가 된다.
            long maxAttempts = BOUND_WINDOW_SECONDS * 1_000L / CIRCUIT_COOLDOWN_MS + 1;
            assertThat(executeCalls.get()).isPositive().isLessThanOrEqualTo((int) maxAttempts);
            assertThat(recordedDelays)
                    .allSatisfy(d -> assertThat(d).isGreaterThanOrEqualTo(30_000L));
        }
    }

    @Test
    @DisplayName("AC-8: 회로차단기가 발동 중이어도 REQ-WS-022 임계값(연속 5회 재연결 실패)은 그대로 안전모드에 진입시킨다")
    void circuitOpen_doesNotCancelReconnectFailureSafeModeThreshold() throws Exception {
        // Arrange — 시계를 고정해 회로차단기 판정 창이 테스트 내내 열려 있도록 한다(벽시계 의존 제거)
        String alias = "auth-circuit-it-ac8-alias";
        Clock frozenClock =
                Clock.fixed(Instant.parse("2026-09-28T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        List<Long> recordedDelays = new CopyOnWriteArrayList<>();
        Sleeper recordingSleeper = recordedDelays::add;
        KisWebSocketMessageHandler handler =
                new KisWebSocketMessageHandler(
                        alias, mock(KisTickPublisher.class), longTtlSafeModeManager, frozenClock);
        WebSocketClient webSocketClient = mock(WebSocketClient.class);
        KisMarketSchedule marketSchedule = mock(KisMarketSchedule.class);
        when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
        when(marketSchedule.isOverseasOpen(any())).thenReturn(false);

        try (WebSocketSession rawSession = mock(WebSocketSession.class)) {
            when(rawSession.isOpen()).thenReturn(true);
            @SuppressWarnings("unchecked")
            CompletableFuture<WebSocketSession> handshakeFuture = mock(CompletableFuture.class);
            when(handshakeFuture.get()).thenReturn(rawSession);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);

            KisWebSocketSession session =
                    new KisWebSocketSession(
                            alias,
                            APPROVAL_KEY,
                            webSocketClient,
                            handler,
                            marketSchedule,
                            longTtlSafeModeManager,
                            recordingSleeper,
                            frozenClock);
            session.connect(WS_URL);

            // 이후 모든 재연결 핸드셰이크가 실패하도록 전환
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenThrow(new IllegalStateException("핸드셰이크 실패"));

            // 회로차단기 발동(판정 창 내 인증 실패 3회 = 임계값) — 세이프모드 카운터(handler)와 무관하게 세션 상태만 갱신
            for (int i = 0; i < 3; i++) {
                session.recordAuthFailure();
            }
            assertThat(longTtlSafeModeManager.isActive(alias)).isFalse();

            // Act — 재연결 5회 연속 실패
            ZonedDateTime marketOpen = ZonedDateTime.now(frozenClock);
            for (int i = 0; i < 5; i++) {
                session.handleDisconnect(marketOpen);
            }

            // Assert — 두 방어선이 서로를 무력화하지 않는다: (1) 5회 실패 후 안전모드 진입, (2) 그 5회 모두 회로차단기 지연 하한이 적용됨
            assertThat(longTtlSafeModeManager.isActive(alias)).isTrue();
            assertThat(recordedDelays).hasSize(5);
            assertThat(recordedDelays)
                    .allSatisfy(d -> assertThat(d).isGreaterThanOrEqualTo(CIRCUIT_COOLDOWN_MS));
        }
    }
}
