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

    /** 연속 구독 실패 횟수 (REQ-WS-016). */
    private final AtomicInteger subscriptionFailureCount = new AtomicInteger(0);

    public KisWebSocketMessageHandler(
            String alias,
            KisTickPublisher tickPublisher,
            SafeModeManager webSocketSafeModeManager) {
        this.alias = alias;
        this.tickPublisher = tickPublisher;
        this.webSocketSafeModeManager = webSocketSafeModeManager;
    }

    // ──────────────────────────────────────────────────────────────────
    // 공개 메서드 (테스트용)
    // ──────────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────────
    // 핵심 메시지 처리
    // ──────────────────────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        if (raw.isEmpty()) {
            return;
        }

        char first = raw.charAt(0);
        if (first == '0' || first == '1') {
            handleTypeA(session, raw);
        } else if (first == '{') {
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
    private void handleTypeA(WebSocketSession session, String raw) {
        // 최대 4개 부분으로 분리: [encryptFlag, trId, count, dataPayload]
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 4) {
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
        if ("1".equals(encryptFlag)) {
            AesKey aesKey = aesKeys.get(trId);
            if (aesKey == null) {
                log.warn("[{}] AES 키 없음 (trId={}), 메시지 무시", alias, trId);
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
     * <p>PINGPONG, 구독 성공(rt_cd=0), 구독 실패(rt_cd≠0), UNSUB 응답을 처리한다.
     */
    private void handleTypeB(WebSocketSession session, String raw) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            JsonNode header = root.path("header");
            JsonNode body = root.path("body");

            // PINGPONG: header.tr_id = "PINGPONG", body 없음
            String trId = header.path("tr_id").asText();
            if ("PINGPONG".equals(trId) && body.isMissingNode()) {
                handlePingPong(session, raw);
                return;
            }

            // body가 없는 PINGPONG 변형 처리
            if ("PINGPONG".equals(trId)) {
                handlePingPong(session, raw);
                return;
            }

            String rtCd = body.path("rt_cd").asText();
            String msg1 = body.path("msg1").asText();

            if ("0".equals(rtCd)) {
                // 구독 성공 또는 UNSUB 확인
                if (msg1.startsWith(UNSUB_PREFIX)) {
                    log.info("[{}] 구독 해제 확인: trId={}, msg={}", alias, trId, msg1);
                } else {
                    // AES 키 저장
                    JsonNode output = body.path("output");
                    if (!output.isMissingNode()) {
                        String iv = output.path("iv").asText();
                        String key = output.path("key").asText();
                        registerAesKey(trId, new AesKey(iv, key));
                        log.info("[{}] AES 키 저장: trId={}", alias, trId);
                    }
                    // 연속 실패 카운터 초기화
                    subscriptionFailureCount.set(0);
                }
            } else {
                // 구독 실패
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

    /** PINGPONG 메시지에 PongMessage로 응답. */
    private static void handlePingPong(WebSocketSession session, String raw) {
        try {
            session.sendMessage(
                    new PongMessage(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8))));
            log.debug("PINGPONG 응답 전송");
        } catch (Exception e) {
            log.error("PINGPONG 응답 전송 실패", e);
        }
    }
}
