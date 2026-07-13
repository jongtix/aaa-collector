package com.aaa.collector.kis.websocket;

import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * KIS WebSocket 단일 세션 래퍼.
 *
 * <p>연결, 구독 맵, 재연결({@link ExponentialBackoff}), 안전 모드를 캡슐화한다. Spring {@code @Component} 아님 — {@link
 * KisWebSocketSessionManager}가 alias별로 직접 생성한다.
 */
// @MX:ANCHOR: [AUTO] KisWebSocketSessionManager에서 생성·관리하는 세션 단위 래퍼
// @MX:REASON: alias별 상태(구독 맵, 재연결 카운터, closed 플래그)를 캡슐화하는 핵심 경계점
@Slf4j
public class KisWebSocketSession {

    /** 재연결 5회 연속 실패 시 안전 모드 진입 임계값 (REQ-WS-022). */
    private static final int SAFE_MODE_RECONNECT_THRESHOLD = 5;

    /** 재연결 기본 지연 (밀리초). */
    private static final long BASE_RECONNECT_DELAY_MS = 1_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** ConcurrentWebSocketSessionDecorator 전송 타임아웃 (밀리초). */
    private static final int SEND_TIME_LIMIT_MS = 10_000;

    /** ConcurrentWebSocketSessionDecorator 버퍼 크기 (64KB). */
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    /** "trId|trKey" 구독 키 분리 시 예상 파트 수. */
    private static final int SUBSCRIPTION_KEY_PARTS = 2;

    private final String alias;
    private final String approvalKey;
    private final WebSocketClient webSocketClient;
    private final KisWebSocketMessageHandler messageHandler;
    private final KisMarketSchedule marketSchedule;
    private final SafeModeManager webSocketSafeModeManager;
    private final Sleeper sleeper;
    private final Clock clock;

    /** 원시 WebSocketSession (연결 해제 시 null). */
    @SuppressWarnings("PMD.AvoidUsingVolatile")
    private volatile WebSocketSession rawSession;

    /** 스레드 안전 전송을 위한 데코레이터 래퍼. */
    @SuppressWarnings("PMD.AvoidUsingVolatile")
    private volatile ConcurrentWebSocketSessionDecorator session;

    /** 현재 활성 구독 키 집합 (trId + "|" + trKey). */
    // @MX:WARN: [AUTO] 세션 외부에서 직접 접근하지 말 것
    // @MX:REASON: ConcurrentHashMap.newKeySet()으로 스레드 안전하게 관리되나, subscribe/unsubscribe 호출 순서 보장은
    // 호출자 책임
    private final Set<String> activeSubscriptions = ConcurrentHashMap.newKeySet();

    /** 연속 재연결 시도 횟수 (성공 시 0으로 초기화). */
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    /** {@code true}이면 정상 종료로 인한 disconnect — 재연결 차단. */
    @SuppressWarnings("PMD.AvoidUsingVolatile")
    private volatile boolean closed;

    /** WebSocket 연결 URL (재연결 시 재사용). */
    @SuppressWarnings("PMD.AvoidUsingVolatile")
    private volatile String wsUrl;

    public KisWebSocketSession(
            String alias,
            String approvalKey,
            WebSocketClient webSocketClient,
            KisWebSocketMessageHandler messageHandler,
            KisMarketSchedule marketSchedule,
            SafeModeManager webSocketSafeModeManager,
            Sleeper sleeper,
            Clock clock) {
        this.alias = alias;
        this.approvalKey = approvalKey;
        this.webSocketClient = webSocketClient;
        this.messageHandler = messageHandler;
        this.marketSchedule = marketSchedule;
        this.webSocketSafeModeManager = webSocketSafeModeManager;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    // ──────────────────────────────────────────────────────────────────
    // 공개 메서드
    // ──────────────────────────────────────────────────────────────────

    /**
     * WebSocket 연결을 수립한다.
     *
     * <p>KIS WebSocket 연결은 HTTP 헤더가 아닌 SUBSCRIBE 메시지 바디에 approval_key를 포함하므로, 연결 시 별도 헤더를 추가하지 않는다.
     *
     * @param url KIS WebSocket 엔드포인트 URL
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidThrowingRawExceptionTypes"})
    public void connect(String url) {
        this.wsUrl = url;
        try {
            CompletableFuture<WebSocketSession> future =
                    webSocketClient.execute(
                            messageHandler, new WebSocketHttpHeaders(), URI.create(url));
            this.rawSession = future.get();
            this.session =
                    new ConcurrentWebSocketSessionDecorator(
                            rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
            reconnectAttempt.set(0);
            log.info("[{}] WebSocket 연결 수립: {}", alias, url);
        } catch (Exception e) {
            log.error("[{}] WebSocket 연결 실패: {}", alias, url, e);
            throw new RuntimeException("WebSocket 연결 실패: " + alias, e);
        }
    }

    /**
     * 종목을 구독한다.
     *
     * <p>KIS SUBSCRIBE 메시지 바디에 approval_key, tr_id, tr_key를 포함하여 전송한다. 전송 성공 시에만 {@code
     * activeSubscriptions}에 추가한다(REQ-WSRES-015, AC-16) — 전송 실패를 구독 성공으로 오계수하지 않기 위함이다.
     *
     * @param trId 트랜잭션 ID (예: H0STCNT0)
     * @param trKey 종목 코드 (예: 005930)
     * @return 전송 성공 시 {@code true}, 실패 시 {@code false}
     */
    public boolean subscribe(String trId, String trKey) {
        String json = buildSubscribeJson(trId, trKey, "1");
        // 전송 직전 방향 기록 — 응답 exact-key 상관의 기준(REQ-WSRES-005, REQ-WSRES-010)
        messageHandler.recordPending(trId, trKey, KisWebSocketMessageHandler.Direction.SUBSCRIBE);
        boolean sent = sendMessage(json);
        if (sent) {
            activeSubscriptions.add(trId + "|" + trKey);
            log.debug("[{}] 구독 추가: trId={}, trKey={}", alias, trId, trKey);
        }
        return sent;
    }

    /**
     * 종목 구독을 해제한다.
     *
     * @param trId 트랜잭션 ID
     * @param trKey 종목 코드
     * @return 전송 성공 시 {@code true}, 실패 시 {@code false}
     */
    public boolean unsubscribe(String trId, String trKey) {
        String json = buildSubscribeJson(trId, trKey, "2");
        // 전송 직전 방향 기록 — 응답 exact-key 상관의 기준(REQ-WSRES-005)
        messageHandler.recordPending(trId, trKey, KisWebSocketMessageHandler.Direction.UNSUBSCRIBE);
        boolean sent = sendMessage(json);
        activeSubscriptions.remove(trId + "|" + trKey);
        log.debug("[{}] 구독 해제: trId={}, trKey={}", alias, trId, trKey);
        return sent;
    }

    /**
     * 모든 활성 구독을 해제한다.
     *
     * <p>각 구독에 대해 unsubscribe 메시지를 전송하고 activeSubscriptions를 비운다. 전송 실패 건수는 WARN 로그로만 남기고(동작 변경
     * 최소), 개별 실패로 전체 해제를 중단하지 않는다. 각 요청도 방향 기록을 거쳐 응답이 exact-key로 상관되도록 한다(REQ-WSRES-005) — 장 종료
     * UNSUBSCRIBE 스톰에 대한 오류 응답이 세이프모드를 오염시키지 않는 실제 장애 시나리오의 핵심 경로다(acceptance.md AC-5).
     */
    public void unsubscribeAll() {
        int failureCount = 0;
        for (String key : activeSubscriptions) {
            String[] parts = key.split("\\|", 2);
            if (parts.length == SUBSCRIPTION_KEY_PARTS) {
                String json = buildSubscribeJson(parts[0], parts[1], "2");
                messageHandler.recordPending(
                        parts[0], parts[1], KisWebSocketMessageHandler.Direction.UNSUBSCRIBE);
                if (!sendMessage(json)) {
                    failureCount++;
                }
                log.debug("[{}] 전체 해제 — trId={}, trKey={}", alias, parts[0], parts[1]);
            }
        }
        activeSubscriptions.clear();
        if (failureCount > 0) {
            log.warn("[{}] 전체 구독 해제 중 {}건 전송 실패", alias, failureCount);
        }
    }

    /**
     * 세션을 정상 종료한다.
     *
     * <p>재연결 차단 플래그를 설정하고, 모든 구독 해제 후 WebSocket 연결을 닫는다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void close() {
        closed = true;
        try {
            unsubscribeAll();
            if (session != null && session.isOpen()) {
                session.close();
            }
            log.info("[{}] WebSocket 세션 정상 종료", alias);
        } catch (Exception e) {
            log.warn("[{}] 세션 종료 중 오류", alias, e);
        }
    }

    /**
     * 연결 끊김 이벤트를 처리한다 (Spring lifecycle 연동용).
     *
     * <p>{@link KisWebSocketMessageHandler#afterConnectionClosed}에서 호출된다. 별도 Virtual Thread를 생성하여
     * 비동기 실행함으로써 Spring WebSocket 이벤트 스레드를 블로킹하지 않는다 (MA-04, REQ-WS-020).
     *
     * <p>테스트에서는 {@link #handleDisconnect(ZonedDateTime)}를 직접 호출하여 동기적으로 검증한다.
     */
    public void handleDisconnect() {
        Thread.ofVirtual()
                .name("ws-reconnect-" + alias)
                .start(() -> handleDisconnect(ZonedDateTime.now(clock)));
    }

    /**
     * 연결 끊김 이벤트를 처리한다.
     *
     * <p>정상 종료 또는 장외 시간이면 재연결하지 않는다. 장중이면 ExponentialBackoff를 적용하여 재연결을 시도하고, 5회 연속 실패 시 안전 모드에
     * 진입한다 (REQ-WS-021, REQ-WS-022).
     *
     * @param now 현재 시각
     */
    public void handleDisconnect(ZonedDateTime now) {
        if (closed) {
            log.debug("[{}] 정상 종료 후 disconnect 이벤트 — 무시", alias);
            return;
        }

        boolean marketOpen =
                marketSchedule.isDomesticOpen(now) || marketSchedule.isOverseasOpen(now);
        if (!marketOpen) {
            log.info("[{}] 장외 시간 disconnect — 재연결 하지 않음 (REQ-WS-021)", alias);
            return;
        }

        attemptReconnect();
    }

    /**
     * 현재 활성 구독 수를 반환한다.
     *
     * @return 구독 수
     */
    public int getSubscriptionCount() {
        return activeSubscriptions.size();
    }

    /**
     * 안전 모드 활성화 여부를 반환한다.
     *
     * @return 안전 모드 활성화 시 {@code true}
     */
    public boolean isInSafeMode() {
        return webSocketSafeModeManager.isActive(alias);
    }

    /**
     * 세션 식별자(alias)를 반환한다.
     *
     * @return alias
     */
    public String getAlias() {
        return alias;
    }

    /**
     * 현재 WebSocket 연결 상태를 반환한다 (REQ-OBSV-011).
     *
     * <p>원시 세션이 존재하고 열려 있으면 연결 상태로 간주한다. 연결 해제 시 {@code rawSession}이 {@code null}로 설정된다.
     *
     * @return 연결되어 있으면 {@code true}
     */
    public boolean isConnected() {
        WebSocketSession current = this.rawSession;
        return current != null && current.isOpen();
    }

    // ──────────────────────────────────────────────────────────────────
    // 내부 메서드
    // ──────────────────────────────────────────────────────────────────

    /**
     * 지수 백오프를 적용하여 재연결을 시도한다.
     *
     * <p>5회 연속 실패 시 안전 모드에 진입하고 재시도를 중단한다 (REQ-WS-022).
     */
    private void attemptReconnect() {
        int attempt = reconnectAttempt.getAndIncrement();
        long delayMs = ExponentialBackoff.delay(attempt, BASE_RECONNECT_DELAY_MS).toMillis();

        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] 재연결 대기 중 인터럽트", alias);
            return;
        }

        reconnectInternal(attempt);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void reconnectInternal(int attempt) {
        try {
            connect(wsUrl);
            int resubscribed = resubscribeAll();
            log.warn(
                    "[{}] 재연결 성공 (attempt={}) — 재구독 {}건 재전송, 데이터 갭 발생 가능 (REQ-WS-026,"
                            + " REQ-WSRES-004)",
                    alias,
                    attempt,
                    resubscribed);
            reconnectAttempt.set(0);
        } catch (Exception e) {
            int currentAttempt = reconnectAttempt.get();
            // connect()는 RuntimeException("WebSocket 연결 실패: alias", cause)을 던짐 —
            // WS handshake 오류이며 appkey/token을 포함하지 않으므로 throwable 로깅 안전
            log.warn(
                    "[{}] 재연결 실패 (attempt={}, exceptionType={}): {}",
                    alias,
                    attempt,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            if (currentAttempt >= SAFE_MODE_RECONNECT_THRESHOLD) {
                log.error("[{}] 재연결 {}회 연속 실패 — 안전 모드 진입 (REQ-WS-022)", alias, currentAttempt);
                webSocketSafeModeManager.enter(alias, e);
            }
        }
    }

    /**
     * 재연결 성공 직후 끊김 전 활성 구독 전체를 재구독한다(REQ-WSRES-001, REQ-WSRES-002, REQ-WSRES-010).
     *
     * <p>매니저 레벨 재분배(종목 선정 로직, {@code SubscriptionTargetResolver})를 재사용하지 않고, 세션이 이미 보유한 {@code
     * activeSubscriptions}만으로 SUBSCRIBE를 재전송한다(REQ-WSRES-002) — 세션 1개의 재연결 사건이 전체 세션 분배를 흔들지 않도록
     * 한다. {@link #subscribe} 메서드를 그대로 재사용하여 방향 기록({@code recordPending}) 경로에 편입시킨다(REQ-WSRES-010) —
     * 재구독 성공 응답도 신규 구독과 동일하게 exact-key로 상관되어 AES 키가 재등록된다(acceptance.md AC-9).
     *
     * @return 재구독을 시도한 건수
     */
    private int resubscribeAll() {
        Set<String> snapshot = Set.copyOf(activeSubscriptions);
        int count = 0;
        for (String key : snapshot) {
            String[] parts = key.split("\\|", 2);
            if (parts.length == SUBSCRIPTION_KEY_PARTS) {
                subscribe(parts[0], parts[1]);
                count++;
            }
        }
        return count;
    }

    /** KIS SUBSCRIBE/UNSUBSCRIBE JSON 메시지를 생성한다. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String buildSubscribeJson(String trId, String trKey, String trType) {
        try {
            ObjectNode header =
                    OBJECT_MAPPER
                            .createObjectNode()
                            .put("approval_key", approvalKey)
                            .put("custtype", "P")
                            .put("tr_type", trType)
                            .put("content-type", "utf-8");

            ObjectNode input =
                    OBJECT_MAPPER.createObjectNode().put("tr_id", trId).put("tr_key", trKey);

            ObjectNode body = OBJECT_MAPPER.createObjectNode().set("input", input);

            return OBJECT_MAPPER
                    .createObjectNode()
                    .<ObjectNode>set("header", header)
                    .set("body", body)
                    .toString();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "SUBSCRIBE JSON 생성 실패: trId=" + trId + ", trKey=" + trKey, e);
        }
    }

    /**
     * 세션에 TextMessage를 전송한다.
     *
     * @param json 전송할 JSON 페이로드
     * @return 전송 성공 시 {@code true}, 세션 미개방 또는 예외 발생 시 {@code false}(REQ-WSRES-015)
     */
    // target은 외부에서 주입된 세션 참조이므로 여기서 close하지 않는다.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CloseResource"})
    private boolean sendMessage(String json) {
        try {
            WebSocketSession target = (session != null) ? session : rawSession;
            if (target != null && target.isOpen()) {
                target.sendMessage(new TextMessage(json));
                return true;
            } else {
                log.warn("[{}] 세션이 열려 있지 않아 메시지 전송 생략", alias);
                return false;
            }
        } catch (Exception e) {
            log.error("[{}] WebSocket 메시지 전송 실패", alias, e);
            return false;
        }
    }
}
