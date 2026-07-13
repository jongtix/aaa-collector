package com.aaa.collector.kis.websocket;

import com.aaa.collector.common.logging.TraceIdManager;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * KIS WebSocket 메시지 핸들러 (Type A/B 파싱, AES 복호화, PINGPONG 응답).
 *
 * <p>Spring {@code @Component}로 등록하지 않는다. {@link KisWebSocketSession}이 세션별로 직접 생성한다.
 */
// @MX:ANCHOR: [AUTO] KisWebSocketSession에서 생성, KisWebSocketScheduler에서 사용 예정
// @MX:REASON: 세션별 핸들러 생성 패턴 — 빈 등록 없이 동작하는 핵심 메시지 처리 경계점
@Slf4j
public class KisWebSocketMessageHandler extends TextWebSocketHandler {

    /** 구독 실패 5회 이상 시 안전 모드 진입 임계값 (REQ-WS-016). */
    private static final int SAFE_MODE_FAILURE_THRESHOLD = 5;

    /** UNSUB 응답 식별 접두사 (REQ-WS-015). */
    private static final String UNSUB_PREFIX = "UNSUB";

    /** 국내 실시간 데이터 trId 목록. */
    private static final Set<String> DOMESTIC_TR_IDS = Set.of("H0STCNT0", "H0STASP0");

    /** Type A 메시지 최대 분리 파트 수. */
    private static final int TYPE_A_SPLIT_LIMIT = 4;

    /** Type A 메시지 암호화 플래그 (암호화). */
    private static final String ENCRYPT_FLAG = "1";

    /** Type B 성공 결과 코드. */
    private static final String RT_CD_SUCCESS = "0";

    /** KIS PINGPONG 트랜잭션 ID. */
    private static final String TR_ID_PINGPONG = "PINGPONG";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 세션 식별자 (안전 모드 alias로 사용). */
    private final String alias;

    private final KisTickPublisher tickPublisher;
    private final SafeModeManager webSocketSafeModeManager;

    /**
     * trId 기준 AES 키 맵. Type A 메시지의 헤더에서 trId를 사용할 수 있으므로 trId를 키로 저장한다. 동시 접근을 대비하여
     * ConcurrentHashMap 사용.
     */
    // @MX:WARN: [AUTO] ConcurrentHashMap으로 스레드 안전하게 관리되나, AES 키가 없을 때 복호화 실패 가능
    // @MX:REASON: 구독 성공 응답(Type B rt_cd=0) 수신 전 Type A 도착 시 NPE 방지 처리 필요
    private final Map<String, AesKey> aesKeys = new ConcurrentHashMap<>();

    /**
     * 요청 방향 — 구독 등록(SUBSCRIBE) 또는 해제(UNSUBSCRIBE)(REQ-WSRES-005~010).
     *
     * <p>{@code pendingByKey}의 값 타입으로 사용되며, {@link #handleTypeB}가 응답을 정확히 어느 방향의 요청으로 상관시킬지 결정하는 데
     * 쓰인다.
     */
    public enum Direction {
        SUBSCRIBE,
        UNSUBSCRIBE
    }

    /**
     * {@code trId:trKey} 복합키 → 요청 방향 매핑(REQ-WSRES-005, exact-key 상관, v0.2.0 재설계).
     *
     * <p>{@link KisWebSocketSession#subscribe}/{@link KisWebSocketSession#unsubscribe}(및 재구독 리플레이)가
     * 전송 직전 {@link #recordPending}으로 기록하고, {@link #handleTypeB}가 {@code header.tr_key} 기반으로 정확히
     * 조회·제거하여 방향을 확정한다. 동일 키에 대한 신규 기록은 이전 기록을 덮어쓴다(가장 최근 요청의 방향이 다음 응답의 방향이라는 자연스러운 의미론 — 순서 보존이
     * 불필요하므로 FIFO 큐보다 단순하다).
     *
     * <p><b>[알려진 한계, 명시적 수용 — acceptance.md 엣지 케이스 D2]</b> 동일 {@code trId:trKey}에 대해 두 응답 모두 도착하기
     * 전에 방향이 바뀌는 요청(등록 직후 즉시 해제 등)이 겹치면, 먼저 도착한 응답이 실제로는 다른 요청의 응답일 수 있다. 이 한계를 nonce 등으로 완전히 제거하지
     * 않는 이유: (1) {@code addSymbol}/{@code removeSymbol}(증분 구독 변경 API)이 현재 호출자 0건(미사용 경로), (2) 원
     * 장애(장 종료 UNSUBSCRIBE 스톰)는 이 경합과 무관, (3) 실패 모드가 침묵적이나 비파괴적(AES 키 미갱신 정도). 증분 구독 변경에 실제 호출자가 생기는
     * 후속 SPEC에서 재평가한다.
     */
    // @MX:WARN: [AUTO] 동일 trId:trKey에 대해 응답 도착 전 방향이 바뀌면(등록 직후 즉시 해제 등) 나중 요청이 덮어써 먼저
    // 도착한 응답이 잘못 상관될 수 있음
    // @MX:REASON: addSymbol/removeSymbol 호출자가 현재 0건(미사용 경로) — 증분 구독 변경 후속 SPEC에서 재평가
    // (acceptance.md 엣지 케이스 D2)
    private final Map<String, Direction> pendingByKey = new ConcurrentHashMap<>();

    /** 연속 구독 실패 횟수 (REQ-WS-016). */
    private final AtomicInteger subscriptionFailureCount = new AtomicInteger(0);

    /**
     * 세션 연결 끊김 시 호출되는 콜백. {@link KisWebSocketSession#handleDisconnect()}로 연결된다.
     *
     * <p>기본값은 no-op. {@link #setDisconnectCallback(Runnable)}으로 주입한다.
     */
    @SuppressWarnings("PMD.AvoidUsingVolatile")
    private volatile Runnable disconnectCallback = () -> {};

    public KisWebSocketMessageHandler(
            String alias,
            KisTickPublisher tickPublisher,
            SafeModeManager webSocketSafeModeManager) {
        super();
        this.alias = alias;
        this.tickPublisher = tickPublisher;
        this.webSocketSafeModeManager = webSocketSafeModeManager;
    }

    // ──────────────────────────────────────────────────────────────────
    // 공개 메서드 (라이프사이클 + 테스트용)
    // ──────────────────────────────────────────────────────────────────

    /**
     * 연결 끊김 콜백을 등록한다.
     *
     * <p>{@link KisWebSocketSessionManager#createDefaultSession}에서 세션 생성 직후 호출되어 {@link
     * KisWebSocketSession#handleDisconnect()}로 연결된다. Spring의 {@link
     * #afterConnectionClosed(WebSocketSession, CloseStatus)} 이벤트가 발생하면 이 콜백이 실행된다 (REQ-WS-020).
     *
     * @param callback 연결 끊김 시 실행할 콜백
     */
    public void setDisconnectCallback(Runnable callback) {
        this.disconnectCallback = callback;
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("[{}] WebSocket 연결 끊김 — status={}", alias, status);
        try {
            disconnectCallback.run();
        } catch (Exception e) {
            log.error("[{}] disconnect 콜백 처리 중 오류 — 재연결 시도 실패", alias, e);
        }
    }

    /**
     * AES 키를 등록한다. 구독 성공 응답(Type B rt_cd=0) 처리 또는 테스트에서 직접 호출한다.
     *
     * @param trId 트랜잭션 ID
     * @param aesKey IV + Key 쌍
     */
    public void registerAesKey(String trId, AesKey aesKey) {
        aesKeys.put(trId, aesKey);
    }

    /**
     * 저장된 AES 키를 반환한다 (테스트 검증용).
     *
     * @param trId 트랜잭션 ID
     * @return AES 키, 없으면 null
     */
    public AesKey getAesKey(String trId) {
        return aesKeys.get(trId);
    }

    /**
     * 구독 등록/해제 요청의 방향을 전송 직전에 기록한다(REQ-WSRES-005, REQ-WSRES-010).
     *
     * <p>{@link KisWebSocketSession#subscribe}/{@link KisWebSocketSession#unsubscribe}(및 재구독 리플레이
     * {@code resubscribeAll})가 {@code sendMessage} 직전에 호출한다. 동일 {@code trId:trKey} 키에 대한 신규 기록은 이전
     * 기록을 덮어쓴다.
     *
     * @param trId 트랜잭션 ID
     * @param trKey 종목 코드
     * @param direction 요청 방향(SUBSCRIBE/UNSUBSCRIBE)
     */
    public void recordPending(String trId, String trKey, Direction direction) {
        pendingByKey.put(trId + ":" + trKey, direction);
    }

    // ──────────────────────────────────────────────────────────────────
    // 핵심 메시지 처리
    // ──────────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        if (raw.isEmpty()) {
            return;
        }

        char first = raw.charAt(0);
        if (first == '0' || first == '1') { // '0'=비암호화, '1'=암호화 Type A 식별자
            handleTypeA(session, raw);
        } else if (first == '{') { // JSON 시작: Type B 제어 메시지
            handleTypeB(session, raw);
        } else {
            log.warn("[{}] 알 수 없는 메시지 형식: {}", alias, raw.substring(0, Math.min(50, raw.length())));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type A 처리: 실시간 데이터
    // ──────────────────────────────────────────────────────────────────

    /**
     * Type A 메시지 처리.
     *
     * <p>메시지 형식: {@code encryptFlag|trId|count|dataPayload}
     *
     * @param session WebSocket 세션
     * @param raw 원본 메시지
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleTypeA(WebSocketSession session, String raw) {
        // 최대 TYPE_A_SPLIT_LIMIT개 부분으로 분리: [encryptFlag, trId, count, dataPayload]
        String[] parts = raw.split("\\|", TYPE_A_SPLIT_LIMIT);
        if (parts.length < TYPE_A_SPLIT_LIMIT) {
            log.warn(
                    "[{}] Type A 메시지 형식 오류: {}",
                    alias,
                    raw.substring(0, Math.min(50, raw.length())));
            return;
        }

        String encryptFlag = parts[0];
        String trId = parts[1];
        String dataPayload = parts[3];

        // 암호화된 경우 AES 복호화
        String decryptedData;
        if (ENCRYPT_FLAG.equals(encryptFlag)) {
            AesKey aesKey = aesKeys.get(trId);
            if (aesKey == null) {
                // AES 키 없이 복호화 불가 → 틱 데이터 유실 (구독 성공 응답 수신 전 도착한 경우)
                log.warn(
                        "[{}] AES 키 없음 — 틱 데이터 유실 (데이터 유실) — trId={}, trKey(raw prefix)={}",
                        alias,
                        trId,
                        dataPayload.substring(0, Math.min(20, dataPayload.length())));
                return;
            }
            decryptedData = KisAesDecryptor.decrypt(dataPayload, aesKey.key(), aesKey.iv());
        } else {
            decryptedData = dataPayload;
        }

        // 국내/해외 구분
        boolean isDomestic = DOMESTIC_TR_IDS.contains(trId);

        // trKey는 복호화된 데이터의 첫 번째 파이프(^) 구분 필드
        String trKey = extractTrKey(decryptedData);

        // trace_id 생성
        String traceId = TraceIdManager.generate();

        ParsedTick tick = new ParsedTick(trId, trKey, decryptedData, isDomestic, traceId);
        tickPublisher.publish(tick);
    }

    /**
     * 데이터에서 종목 코드(trKey)를 추출한다.
     *
     * <p>KIS 실시간 데이터의 첫 번째 필드('^' 구분자)가 종목 코드다.
     */
    private static String extractTrKey(String data) {
        int idx = data.indexOf('^');
        if (idx > 0) {
            return data.substring(0, idx);
        }
        // '^' 구분자가 없는 경우 전체 데이터를 trKey로 사용
        return data;
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B 처리: 제어 메시지 (구독 응답, PINGPONG)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Type B 메시지 처리.
     *
     * <p>PINGPONG, 구독 등록 응답(성공/실패), 구독 해제 응답을 처리한다. PINGPONG이 아닌 응답은 {@code header.tr_id} + {@code
     * header.tr_key} 복합키로 {@code pendingByKey}를 조회해 방향(등록/해제)을 확정한다(REQ-WSRES-006, exact-key 상관).
     * 키가 없으면 기존 {@code msg1} 접두어 휴리스틱으로 폴백한다(REQ-WSRES-009).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void handleTypeB(WebSocketSession session, String raw) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            JsonNode header = root.path("header");
            JsonNode body = root.path("body");

            // PINGPONG: header.tr_id = "PINGPONG" — 방향 조회 이전에 조기 반환(REQ-WSRES-006)
            String trId = header.path("tr_id").asText();
            if (TR_ID_PINGPONG.equals(trId)) {
                handlePingPong(session, raw, alias);
                return;
            }

            String trKey = header.path("tr_key").asText();
            String rtCd = body.path("rt_cd").asText();
            String msg1 = body.path("msg1").asText();

            Direction direction = pendingByKey.remove(trId + ":" + trKey);
            if (direction == null) {
                // 상관 정보 소실 — 기존 문자열 휴리스틱으로 폴백(REQ-WSRES-009, AC-8)
                direction =
                        msg1.startsWith(UNSUB_PREFIX) ? Direction.UNSUBSCRIBE : Direction.SUBSCRIBE;
                log.warn("[{}] 요청 상관 실패 — 문자열 폴백 사용", alias);
            }

            if (direction == Direction.UNSUBSCRIBE) {
                // 해제 요청 응답 — 성공/실패 무관하게 구독 실패 카운트 미증가(REQ-WSRES-007)
                if (RT_CD_SUCCESS.equals(rtCd)) {
                    log.info("[{}] 구독 해제 확인: trId={}, msg={}", alias, trId, msg1);
                } else {
                    log.warn("[{}] 구독 해제 오류(무해) — 카운트 미반영: trId={}, msg={}", alias, trId, msg1);
                }
                return;
            }

            // direction == SUBSCRIBE
            if (RT_CD_SUCCESS.equals(rtCd)) {
                handleSubscriptionSuccess(trId, body);
            } else {
                // 구독 실패(REQ-WSRES-008)
                log.warn("[{}] 구독 실패: trId={}, rt_cd={}, msg={}", alias, trId, rtCd, msg1);
                int count = subscriptionFailureCount.incrementAndGet();
                if (count >= SAFE_MODE_FAILURE_THRESHOLD) {
                    webSocketSafeModeManager.enter(
                            alias, new RuntimeException("구독 연속 실패 " + count + "회: " + msg1));
                }
            }

        } catch (Exception e) {
            log.error(
                    "[{}] Type B 메시지 파싱 오류: {}",
                    alias,
                    raw.substring(0, Math.min(200, raw.length())),
                    e);
        }
    }

    /**
     * 구독 성공(UNSUB 제외) 처리 — AES 키 저장, 연속 실패 카운터 초기화, WS 세이프모드 백오프 리셋(REQ-WSRES-014).
     *
     * <p>{@code webSocketSafeModeManager.resetBackoff(alias)}는 세이프모드 활성 여부와 무관하게 항상 호출된다 — TTL 자연
     * 만료 후 재성공하는 가장 흔한 회복 경로에서도 stale 백오프 레벨을 리셋하기 위함이다(SafeModeManager D-F 패턴 미러링).
     */
    private void handleSubscriptionSuccess(String trId, JsonNode body) {
        JsonNode output = body.path("output");
        if (!output.isMissingNode()) {
            String iv = output.path("iv").asText();
            String key = output.path("key").asText();
            registerAesKey(trId, new AesKey(iv, key));
            log.info("[{}] AES 키 저장: trId={}", alias, trId);
        }
        subscriptionFailureCount.set(0);
        webSocketSafeModeManager.resetBackoff(alias);
    }

    /** PINGPONG 메시지에 PongMessage로 응답. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private static void handlePingPong(WebSocketSession session, String raw, String sessionAlias) {
        try {
            session.sendMessage(
                    new PongMessage(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8))));
            log.debug("[{}] PINGPONG 응답 전송", sessionAlias);
        } catch (Exception e) {
            // PINGPONG 전송 실패 = 하트비트 유실 — 세션 식별자를 포함해 식별 가능하게 기록
            log.error("[{}] PINGPONG 응답 전송 실패 (하트비트 유실)", sessionAlias, e);
        }
    }
}
