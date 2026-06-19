package com.aaa.collector.kis.websocket;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import com.aaa.collector.kis.token.KisTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/**
 * KIS WebSocket 5세션 라이프사이클, 라운드로빈 분배, 재연결 총괄.
 *
 * <p>5개 계좌에 대한 세션을 생성·관리하고, 구독 요청을 라운드로빈으로 분배한다. 포화({@value MAX_SUBSCRIPTIONS_PER_SESSION}개 초과) 또는
 * 안전 모드 세션은 분배 대상에서 제외된다.
 */
// @MX:ANCHOR: [AUTO] WebSocket 구독 분배·세션 라이프사이클의 중앙 진입점
// @MX:REASON: openAll/closeAll/assignSubscription이 외부(KisWebSocketScheduler 등)에서 fan_in >= 3으로 호출될
// 예정
@Slf4j
@Component
public class KisWebSocketSessionManager {

    /** KIS WebSocket 엔드포인트 URL. */
    private static final String KIS_WS_URL = "ws://ops.koreainvestment.com:21000";

    /** 세션당 최대 구독 수 (REQ-WS-005). */
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 40;

    /** 활성 구독 종목 수 gauge (틱 충족률 분모, REQ-OBSV-011). */
    static final String ACTIVE_SUBSCRIPTIONS_METRIC = "aaa_collector_tick_active_subscriptions";

    /** 세션별 연결 상태 gauge (connected 1/0, REQ-OBSV-011). */
    static final String SESSION_CONNECTED_METRIC = "aaa_collector_websocket_session_connected";

    private final KisProperties kisProperties;
    private final KisTokenService kisTokenService;
    private final KisTickPublisher tickPublisher;
    private final SafeModeManager webSocketSafeModeManager;
    private final KisMarketSchedule marketSchedule;
    private final Sleeper sleeper;
    private final Clock clock;
    private final KisWebSocketSessionFactory sessionFactory;

    /** alias → 세션 맵. */
    private final Map<String, KisWebSocketSession> sessions = new ConcurrentHashMap<>();

    /** trKey → 소유 alias 맵 (구독 해제 라우팅용). */
    private final Map<String, String> subscriptionOwner = new ConcurrentHashMap<>();

    /** 라운드로빈 인덱스. */
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    @Autowired
    public KisWebSocketSessionManager(
            KisProperties kisProperties,
            KisTokenService kisTokenService,
            KisTickPublisher tickPublisher,
            @Qualifier("webSocketSafeModeManager") SafeModeManager webSocketSafeModeManager,
            KisMarketSchedule marketSchedule,
            Sleeper sleeper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this(
                kisProperties,
                kisTokenService,
                tickPublisher,
                webSocketSafeModeManager,
                marketSchedule,
                sleeper,
                clock,
                null, // 팩토리 null → 기본 팩토리 사용
                meterRegistry);
    }

    /**
     * 테스트 및 프로덕션 공용 생성자.
     *
     * <p>구독/세션 계측 gauge(REQ-OBSV-011)를 생성 시점에 지연 조회 supplier로 등록한다 — 활성 구독 종목 수(분모)와 세션별 연결
     * 상태(1/0). gauge는 내부 상태를 매 스크랩 시 지연 조회하므로 산발적 {@code set()} 호출이 불필요하다.
     *
     * @param sessionFactory null이면 기본 {@link StandardWebSocketClient} 기반 팩토리 사용
     * @param meterRegistry 구독/세션 gauge 등록 대상 레지스트리
     */
    public KisWebSocketSessionManager(
            KisProperties kisProperties,
            KisTokenService kisTokenService,
            KisTickPublisher tickPublisher,
            SafeModeManager webSocketSafeModeManager,
            KisMarketSchedule marketSchedule,
            Sleeper sleeper,
            Clock clock,
            KisWebSocketSessionFactory sessionFactory,
            MeterRegistry meterRegistry) {
        this.kisProperties = kisProperties;
        this.kisTokenService = kisTokenService;
        this.tickPublisher = tickPublisher;
        this.webSocketSafeModeManager = webSocketSafeModeManager;
        this.marketSchedule = marketSchedule;
        this.sleeper = sleeper;
        this.clock = clock;
        this.sessionFactory =
                (sessionFactory != null) ? sessionFactory : this::createDefaultSession;
        registerMetrics(meterRegistry);
    }

    /**
     * 구독/세션 gauge를 supplier 기반으로 등록한다 (REQ-OBSV-011).
     *
     * <p>활성 구독 종목 수는 {@code subscriptionOwner} 복합 키({@code trId:trKey})에서 distinct symbol(trKey)을
     * 세어 동일 종목의 체결·호가 2개 구독이 분모를 부풀리지 않게 한다. 세션 연결 상태는 계좌 alias별 1개 gauge로 노출하고, 세션 부재·미연결 시 0이다.
     */
    private void registerMetrics(MeterRegistry meterRegistry) {
        meterRegistry.gauge(
                ACTIVE_SUBSCRIPTIONS_METRIC, this, KisWebSocketSessionManager::activeSymbolCount);
        for (KisAccountCredential credential : kisProperties.accounts()) {
            String alias = credential.alias();
            meterRegistry.gauge(
                    SESSION_CONNECTED_METRIC,
                    Tags.of("alias", alias),
                    this,
                    manager -> manager.sessionConnected(alias));
        }
    }

    /** subscriptionOwner 복합 키에서 distinct symbol(trKey) 수를 센다 (REQ-OBSV-011 분모). */
    private double activeSymbolCount() {
        Set<String> distinctSymbols =
                subscriptionOwner.keySet().stream()
                        .map(key -> key.substring(key.indexOf(':') + 1))
                        .collect(Collectors.toSet());
        return distinctSymbols.size();
    }

    /** 세션 연결 상태를 1/0으로 반환한다 (세션 부재·미연결 시 0). */
    private double sessionConnected(String alias) {
        KisWebSocketSession session = sessions.get(alias);
        return (session != null && session.isConnected()) ? 1.0 : 0.0;
    }

    // ──────────────────────────────────────────────────────────────────
    // 라이프사이클
    // ──────────────────────────────────────────────────────────────────

    /**
     * 모든 계좌에 대해 WebSocket 세션을 열고 연결한다 (REQ-WS-050).
     *
     * <p>연결 실패 시 approval_key 강제 재발급 후 1회 재시도한다 (REQ-WS-042).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void openAll() {
        log.info("전 계좌 WebSocket 세션 연결 시작 — 계좌 수: {}", kisProperties.accounts().size());

        for (KisAccountCredential credential : kisProperties.accounts()) {
            String alias = credential.alias();
            try {
                connectWithApprovalKeyRetry(alias);
            } catch (Exception e) {
                log.error("[{}] WebSocket 세션 연결 실패", alias, e);
            }
        }

        log.info("전 계좌 WebSocket 세션 연결 완료 — 연결된 세션: {}", sessions.size());
    }

    /**
     * 승인키를 조회·연결하고, 실패 시 승인키를 강제 재발급하여 1회 재시도한다 (REQ-WS-042).
     *
     * <p>실패 원인이 HTTP 401이든 일시 오류든 관계없이 재발급 후 재시도함으로써 인증 오류를 확실히 복구한다.
     *
     * @param alias 계좌 식별자
     * @throws Exception 재시도 후에도 연결 실패 시
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException"})
    private void connectWithApprovalKeyRetry(String alias) throws Exception {
        try {
            String approvalKey = getApprovalKeyWithRetry(alias);
            KisWebSocketSession session = sessionFactory.create(alias, approvalKey);
            session.connect(KIS_WS_URL);
            sessions.put(alias, session);
            log.info("[{}] WebSocket 세션 연결 성공", alias);
        } catch (Exception e) {
            // KisWebSocketSession.connect()는 RuntimeException("WebSocket 연결 실패: alias", cause)을
            // 던짐 — WS handshake 오류이며 appkey/token을 포함하지 않으므로 throwable 로깅 안전
            log.warn(
                    "[{}] WebSocket 연결 실패 ({}) — approval_key 강제 재발급 후 재시도 (REQ-WS-042): {}",
                    alias,
                    e.getClass().getSimpleName(),
                    e.getMessage());
            String freshKey = kisTokenService.reissueApprovalKey(alias);
            KisWebSocketSession session = sessionFactory.create(alias, freshKey);
            session.connect(KIS_WS_URL);
            sessions.put(alias, session);
            log.info("[{}] approval_key 재발급 후 WebSocket 세션 연결 성공", alias);
        }
    }

    /**
     * 모든 세션을 종료한다 (REQ-WS-051).
     *
     * <p>각 세션의 {@link KisWebSocketSession#close()}를 호출하고, sessions 및 subscriptionOwner 맵을 비운다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void closeAll() {
        log.info("전 계좌 WebSocket 세션 종료 시작");
        sessions.values()
                .forEach(
                        session -> {
                            try {
                                session.close();
                            } catch (Exception e) {
                                log.warn("[{}] 세션 종료 중 오류", session.getAlias(), e);
                            }
                        });
        sessions.clear();
        subscriptionOwner.clear();
        log.info("전 계좌 WebSocket 세션 종료 완료");
    }

    // ──────────────────────────────────────────────────────────────────
    // 구독 분배
    // ──────────────────────────────────────────────────────────────────

    /**
     * 구독을 라운드로빈으로 사용 가능한 세션에 할당한다 (REQ-WS-005, REQ-WS-006, REQ-WS-007, REQ-WS-008).
     *
     * <p>포화 또는 안전 모드 세션은 제외한다. 사용 가능한 세션이 없으면 {@code false}를 반환한다.
     *
     * @param trId 트랜잭션 ID
     * @param trKey 종목 코드
     * @return 할당 성공 시 {@code true}, 실패 시 {@code false}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean assignSubscription(String trId, String trKey) {
        List<KisWebSocketSession> available =
                sessions.values().stream()
                        .filter(s -> !s.isInSafeMode())
                        .filter(s -> s.getSubscriptionCount() < MAX_SUBSCRIPTIONS_PER_SESSION)
                        .collect(Collectors.toList());

        if (available.isEmpty()) {
            log.error("사용 가능한 WebSocket 세션 없음 — 구독 할당 실패: trId={}, trKey={}", trId, trKey);
            return false;
        }

        int idx = Math.abs(roundRobinIndex.getAndIncrement() % available.size());
        KisWebSocketSession target = available.get(idx);

        try {
            target.subscribe(trId, trKey);
            // trId:trKey 복합 키로 저장 — 동일 종목의 체결·호가 구독을 독립적으로 추적
            subscriptionOwner.put(trId + ":" + trKey, target.getAlias());
            if (log.isDebugEnabled()) {
                log.debug("구독 할당: trId={}, trKey={} → alias={}", trId, trKey, target.getAlias());
            }
            return true;
        } catch (Exception e) { // NOSONAR: subscribe()는 다양한 예외를 발생시킬 수 있음
            log.error("구독 할당 중 오류: trId={}, trKey={}", trId, trKey, e);
            return false;
        }
    }

    /**
     * 구독을 해제한다 (REQ-WS-062).
     *
     * <p>subscriptionOwner 맵에서 소유 alias를 조회하여 해당 세션에 구독 해제를 위임한다.
     *
     * @param trId 트랜잭션 ID
     * @param trKey 종목 코드
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void unassignSubscription(String trId, String trKey) {
        // trId:trKey 복합 키로 소유자 조회
        String ownerAlias = subscriptionOwner.remove(trId + ":" + trKey);
        if (ownerAlias == null) {
            log.warn("구독 소유자를 찾을 수 없음: trId={}, trKey={}", trId, trKey);
            return;
        }

        KisWebSocketSession ownerSession = sessions.get(ownerAlias);
        if (ownerSession == null) {
            log.warn("소유 세션이 존재하지 않음: alias={}", ownerAlias);
            return;
        }

        try {
            ownerSession.unsubscribe(trId, trKey);
            log.debug("구독 해제: trId={}, trKey={} ← alias={}", trId, trKey, ownerAlias);
        } catch (Exception e) { // NOSONAR: unsubscribe()는 다양한 예외를 발생시킬 수 있음
            log.error("구독 해제 중 오류: trId={}, trKey={}", trId, trKey, e);
        }
    }

    /**
     * 국내 심볼 목록을 구독한다.
     *
     * <p>각 심볼에 대해 체결(H0STCNT0)과 호가(H0STASP0) 2개 구독을 할당한다. 세션 용량 초과로 할당에 실패하면 이후 모든 심볼도 실패하므로 루프를
     * 중단한다. 부분 구독(CNT 성공 + ASP 실패)은 동일 세션 풀을 공유하므로 실제로 발생하지 않는다.
     *
     * @param domesticSymbols 구독할 국내 종목 코드 목록
     */
    public void subscribeSymbols(List<String> domesticSymbols) {
        for (String symbol : domesticSymbols) {
            boolean cnt = assignSubscription("H0STCNT0", symbol);
            boolean asp = assignSubscription("H0STASP0", symbol);
            if (!cnt || !asp) {
                log.warn(
                        "심볼 구독 실패 (cnt={}, asp={}) — 세션 용량 초과, 이후 종목 스킵: symbol={}",
                        cnt,
                        asp,
                        symbol);
                break;
            }
        }
    }

    /**
     * 단일 종목을 증분 추가한다 (REQ-WS-060, REQ-WS-061).
     *
     * <p>세션 재연결 없이 기존 연결에 SUBSCRIBE 메시지를 전송한다.
     *
     * @param symbol 종목 코드
     */
    public void addSymbol(String symbol) {
        boolean cnt = assignSubscription("H0STCNT0", symbol);
        boolean asp = assignSubscription("H0STASP0", symbol);
        if (!cnt || !asp) {
            log.warn("증분 구독 추가 실패 — 세션 용량 부족: symbol={}", symbol);
        }
    }

    /**
     * 단일 종목을 증분 제거한다 (REQ-WS-062).
     *
     * <p>세션 재연결 없이 해당 세션에 UNSUBSCRIBE 메시지를 전송한다.
     *
     * @param symbol 종목 코드
     */
    public void removeSymbol(String symbol) {
        unassignSubscription("H0STCNT0", symbol);
        unassignSubscription("H0STASP0", symbol);
    }

    // ──────────────────────────────────────────────────────────────────
    // 내부 메서드
    // ──────────────────────────────────────────────────────────────────

    /**
     * 승인키를 조회하며, 실패 시 1회 재시도한다 (REQ-WS-042).
     *
     * @param alias 계좌 식별자
     * @return 유효한 승인키
     * @throws RuntimeException 재시도 후에도 실패한 경우
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private String getApprovalKeyWithRetry(String alias) {
        try {
            return kisTokenService.getValidApprovalKey(alias);
        } catch (Exception e) {
            // 승인키는 자격증명 인접 경로다. requestApprovalKey는 appkey/secretkey를 요청 body에 싣고
            // 발생 예외(RestClientResponseException 등)의 message는 응답 본문이라 자격증명을 담지 않으나,
            // 확실성을 위해 예외 message/스택을 로깅하지 않고 예외 타입만 기록한다.
            log.warn(
                    "[{}] 승인키 조회 실패 — 1회 재시도 (REQ-WS-042): exceptionType={}",
                    alias,
                    e.getClass().getSimpleName());
            return kisTokenService.getValidApprovalKey(alias);
        }
    }

    /** 기본 프로덕션 세션 팩토리. */
    private KisWebSocketSession createDefaultSession(String alias, String approvalKey) {
        KisWebSocketMessageHandler handler =
                new KisWebSocketMessageHandler(alias, tickPublisher, webSocketSafeModeManager);
        KisWebSocketSession session =
                new KisWebSocketSession(
                        alias,
                        approvalKey,
                        new StandardWebSocketClient(),
                        handler,
                        marketSchedule,
                        webSocketSafeModeManager,
                        sleeper,
                        clock);
        // afterConnectionClosed → session.handleDisconnect() 경로 연결 (CR-01 fix, REQ-WS-020)
        handler.setDisconnectCallback(session::handleDisconnect);
        return session;
    }
}
