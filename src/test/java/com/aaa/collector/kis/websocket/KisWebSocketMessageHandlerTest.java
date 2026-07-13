package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.common.safemode.SafeModeManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisWebSocketMessageHandler")
class KisWebSocketMessageHandlerTest {

    private static final String ALIAS = "test-alias";
    private static final String TEST_KEY = "12345678901234567890123456789012"; // 32바이트
    private static final String TEST_IV = "1234567890123456"; // 16바이트

    @Mock private KisTickPublisher tickPublisher;
    @Mock private SafeModeManager webSocketSafeModeManager;
    @Mock private WebSocketSession session;

    private KisWebSocketMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new KisWebSocketMessageHandler(ALIAS, tickPublisher, webSocketSafeModeManager);
    }

    // ──────────────────────────────────────────────────────────────────
    // 테스트 헬퍼
    // ──────────────────────────────────────────────────────────────────

    /** 주어진 평문을 AES-256-CBC로 암호화하여 Base64 문자열 반환. */
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(TEST_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(TEST_IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder()
                .encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    // ──────────────────────────────────────────────────────────────────
    // Type A — 암호화 메시지
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type A — 암호화 메시지 (encryptFlag=1)")
    class TypeAEncrypted {

        @Test
        @DisplayName("저장된 AES 키로 복호화 후 ParsedTick 발행")
        void decryptsAndPublishes() throws Exception {
            // Arrange
            String plainData = "005930^72500^72600^72400^1234567^8";
            String base64Cipher = encrypt(plainData);

            // AES 키 사전 저장
            handler.registerAesKey("H0STCNT0", new AesKey(TEST_IV, TEST_KEY));

            String raw = "1|H0STCNT0|001|" + base64Cipher;

            // Act
            handler.handleTextMessage(session, new TextMessage(raw));

            // Assert
            ArgumentCaptor<ParsedTick> tickCaptor = ArgumentCaptor.forClass(ParsedTick.class);
            verify(tickPublisher).publish(tickCaptor.capture());
            ParsedTick tick = tickCaptor.getValue();
            assertThat(tick.trId()).isEqualTo("H0STCNT0");
            assertThat(tick.data()).isEqualTo(plainData);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type A — 평문 메시지
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type A — 평문 메시지 (encryptFlag=0)")
    class TypeAPlain {

        @Test
        @DisplayName("복호화 없이 ParsedTick 발행")
        void publishesWithoutDecryption() {
            // Arrange — KIS 국내 호가 실시간 메시지 형식
            String raw = "0|H0STCNT0|001|005930^72500^72600";

            // Act
            handler.handleTextMessage(session, new TextMessage(raw));

            // Assert
            ArgumentCaptor<ParsedTick> tickCaptor = ArgumentCaptor.forClass(ParsedTick.class);
            verify(tickPublisher).publish(tickCaptor.capture());
            ParsedTick tick = tickCaptor.getValue();
            assertThat(tick.trId()).isEqualTo("H0STCNT0");
            assertThat(tick.data()).isEqualTo("005930^72500^72600");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — PINGPONG
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — PINGPONG")
    class TypeBPingPong {

        @Test
        @DisplayName("PINGPONG 수신 시 PongMessage 전송")
        void sendsPongMessage() throws Exception {
            // Arrange
            String pingpongJson = "{\"header\":{\"tr_id\":\"PINGPONG\",\"tr_key\":\"\"}}";

            // Act
            handler.handleTextMessage(session, new TextMessage(pingpongJson));

            // Assert
            verify(session).sendMessage(any(PongMessage.class));
            verify(tickPublisher, never()).publish(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — 구독 성공 (rt_cd=0)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — 구독 성공 응답 (rt_cd=0)")
    class TypeBSubscribeSuccess {

        @Test
        @DisplayName("AES 키를 trId 기준으로 저장하고 실패 카운터 초기화")
        void storesAesKeyAndResetsFailureCount() {
            // Arrange
            String json =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "0",
                        "msg1": "SUBSCRIBE SUCCESS",
                        "output": {"iv": "testiv1234567890", "key": "testkey1234567890123456789012345"}
                      }
                    }
                    """;

            // Act
            handler.handleTextMessage(session, new TextMessage(json));

            // Assert — AES 키 저장 여부 확인 (registerAesKey를 통해 간접 검증)
            AesKey stored = handler.getAesKey("H0STCNT0");
            assertThat(stored).isNotNull();
            assertThat(stored.iv()).isEqualTo("testiv1234567890");
            assertThat(stored.key()).isEqualTo("testkey1234567890123456789012345");
        }

        @Test
        @DisplayName(
                "AC-13: 구독 성공 시 webSocketSafeModeManager.resetBackoff(alias) 호출(REQ-WSRES-014)")
        void subscriptionSuccess_resetsWebSocketSafeModeBackoff() {
            // Arrange
            String json =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "0",
                        "msg1": "SUBSCRIBE SUCCESS",
                        "output": {"iv": "testiv1234567890", "key": "testkey1234567890123456789012345"}
                      }
                    }
                    """;

            // Act
            handler.handleTextMessage(session, new TextMessage(json));

            // Assert
            verify(webSocketSafeModeManager).resetBackoff(ALIAS);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — 구독 실패 (rt_cd≠0) 반복
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — 구독 실패 응답 (rt_cd≠0)")
    class TypeBSubscribeFailure {

        private static final String FAILURE_JSON =
                """
                {
                  "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                  "body": {
                    "rt_cd": "1",
                    "msg1": "SUBSCRIBE FAIL"
                  }
                }
                """;

        @Test
        @DisplayName("4회 실패 — 안전 모드 미진입")
        void fourFailures_doesNotEnterSafeMode() {
            TextMessage message = new TextMessage(FAILURE_JSON);
            for (int i = 0; i < 4; i++) {
                handler.handleTextMessage(session, message);
            }

            verify(webSocketSafeModeManager, never()).enter(any(), any());
        }

        @Test
        @DisplayName("5회 실패 — 안전 모드 진입")
        void fiveFailures_entersSafeMode() {
            TextMessage message = new TextMessage(FAILURE_JSON);
            for (int i = 0; i < 5; i++) {
                handler.handleTextMessage(session, message);
            }

            verify(webSocketSafeModeManager, times(1)).enter(any(), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // disconnect 콜백 (CR-01, REQ-WS-020)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("disconnect 콜백 — afterConnectionClosed (CR-01)")
    class DisconnectCallback {

        @Test
        @DisplayName("afterConnectionClosed 호출 시 등록된 콜백 실행")
        void afterConnectionClosedTriggersCallback() throws Exception {
            // Arrange
            Runnable callback = mock(Runnable.class);
            handler.setDisconnectCallback(callback);

            // Act
            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            // Assert
            verify(callback).run();
        }

        @Test
        @DisplayName("콜백 미등록 시 afterConnectionClosed 예외 없이 완료")
        void afterConnectionClosedWithDefaultCallbackDoesNotThrow() throws Exception {
            // Act & Assert — 기본 no-op 콜백이므로 예외 없이 완료돼야 한다
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 해외 체결·호가 평문 발행 특성화 (AC-TICK-1, AC-TICK-2, AC-PLAIN-1)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("해외 체결·호가 평문 발행")
    class OverseasPlainTickPublish {

        @Test
        @DisplayName("AC-TICK-1: 해외 체결(HDFSCNT0) 평문 → publish 호출, isDomestic=false")
        void overseasExecutionPlain_publishesWithIsDomesticFalse() {
            // Arrange — 해외 체결 평문 형식 (encryptFlag=0)
            String raw = "0|HDFSCNT0|003|DNASAAPL^150000^149500^150200^100^12345";

            // Act
            handler.handleTextMessage(session, new TextMessage(raw));

            // Assert
            ArgumentCaptor<ParsedTick> captor = ArgumentCaptor.forClass(ParsedTick.class);
            verify(tickPublisher).publish(captor.capture());
            ParsedTick tick = captor.getValue();
            assertThat(tick.trId()).isEqualTo("HDFSCNT0");
            assertThat(tick.isDomestic()).isFalse();
            assertThat(tick.trKey()).isEqualTo("DNASAAPL");
        }

        @Test
        @DisplayName("AC-TICK-2: 해외 호가(HDFSASP0) 평문 → publish 호출, isDomestic=false")
        void overseasQuotePlain_publishesWithIsDomesticFalse() {
            // Arrange — 해외 호가 평문 형식 (encryptFlag=0)
            String raw = "0|HDFSASP0|001|DNASAAPL^150000^150100^149900^149800";

            // Act
            handler.handleTextMessage(session, new TextMessage(raw));

            // Assert
            ArgumentCaptor<ParsedTick> captor = ArgumentCaptor.forClass(ParsedTick.class);
            verify(tickPublisher).publish(captor.capture());
            ParsedTick tick = captor.getValue();
            assertThat(tick.trId()).isEqualTo("HDFSASP0");
            assertThat(tick.isDomestic()).isFalse();
        }

        @Test
        @DisplayName("AC-PLAIN-1: encryptFlag=0 해외 메시지는 AES 키 미조회 (복호화 경로 미통과)")
        void encryptFlagZero_doesNotLookUpAesKey() {
            // Arrange — AES 키 미등록 상태에서 평문 메시지 전송
            // encryptFlag=1이면 aesKeys.get(trId) 조회 후 null이면 return (틱 유실)
            // encryptFlag=0이면 aesKeys 미조회 → publish 호출됨
            String raw = "0|HDFSCNT0|001|DNASAAPL^150000";

            // Act
            handler.handleTextMessage(session, new TextMessage(raw));

            // Assert — AES 키가 없어도 publish 호출됨 (평문 경로)
            verify(tickPublisher, times(1)).publish(any(ParsedTick.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — UNSUB 응답
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — UNSUB 응답")
    class TypeBUnsub {

        @Test
        @DisplayName("UNSUB 수신 시 예외 없이 처리 완료")
        void handlesUnsubWithoutException() {
            // Arrange
            String json =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "0",
                        "msg1": "UNSUBSCRIBE SUCCESS"
                      }
                    }
                    """;

            // Act & Assert
            handler.handleTextMessage(session, new TextMessage(json));
            // 예외가 발생하지 않으면 성공
            verify(tickPublisher, never()).publish(any());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — exact-key 상관 (REQ-WSRES-005~010, AC-5~AC-9)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — exact-key 요청-응답 상관")
    class ExactKeyCorrelation {

        private static final String UNSUB_ERROR_JSON =
                """
                {
                  "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                  "body": {
                    "rt_cd": "1",
                    "msg1": "UNSUBSCRIBE ERROR(not found!)"
                  }
                }
                """;

        private Logger handlerLogger;
        private ListAppender<ILoggingEvent> listAppender;

        @BeforeEach
        void attachLogAppender() {
            handlerLogger = (Logger) LoggerFactory.getLogger(KisWebSocketMessageHandler.class);
            listAppender = new ListAppender<>();
            listAppender.start();
            handlerLogger.addAppender(listAppender);
        }

        @AfterEach
        void detachLogAppender() {
            handlerLogger.detachAppender(listAppender);
            listAppender.stop();
        }

        @Test
        @DisplayName("AC-5: 방향이 UNSUBSCRIBE로 기록된 키에 오류 응답 5회 연속 수신해도 세이프모드 미진입(실측 재현)")
        void unsubscribeDirection_fiveConsecutiveErrorResponses_doesNotEnterSafeMode() {
            TextMessage unsubErrorMessage = new TextMessage(UNSUB_ERROR_JSON);
            for (int i = 0; i < 5; i++) {
                handler.recordPending(
                        "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.UNSUBSCRIBE);
                handler.handleTextMessage(session, unsubErrorMessage);
            }

            verify(webSocketSafeModeManager, never()).enter(any(), any());
        }

        @Test
        @DisplayName("AC-6: 방향이 SUBSCRIBE로 기록된 키에 실패 응답 5회 연속 수신 → 세이프모드 진입(회귀 방지)")
        void subscribeDirection_fiveConsecutiveFailures_entersSafeMode() {
            String subscribeFailureJson =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "1",
                        "msg1": "SUBSCRIBE FAIL"
                      }
                    }
                    """;

            TextMessage subscribeFailureMessage = new TextMessage(subscribeFailureJson);
            for (int i = 0; i < 5; i++) {
                handler.recordPending(
                        "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.SUBSCRIBE);
                handler.handleTextMessage(session, subscribeFailureMessage);
            }

            verify(webSocketSafeModeManager, times(1)).enter(any(), any());
        }

        @Test
        @DisplayName("AC-7: 서로 다른 trKey 응답이 순서 역전되어도 exact-key로 각자 정확히 상관된다")
        void crossKeyOrderReversal_correlatesEachResponseIndependently() {
            // Arrange — 005930은 SUBSCRIBE, 000660은 UNSUBSCRIBE로 기록
            handler.recordPending(
                    "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.SUBSCRIBE);
            handler.recordPending(
                    "H0STCNT0", "000660", KisWebSocketMessageHandler.Direction.UNSUBSCRIBE);

            String unsubscribeErrorFor000660 =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "000660"},
                      "body": {
                        "rt_cd": "1",
                        "msg1": "UNSUBSCRIBE ERROR(not found!)"
                      }
                    }
                    """;
            String subscribeSuccessFor005930 =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "0",
                        "msg1": "SUBSCRIBE SUCCESS",
                        "output": {"iv": "testiv1234567890", "key": "testkey1234567890123456789012345"}
                      }
                    }
                    """;

            // Act — 000660의 UNSUBSCRIBE 오류가 005930의 SUBSCRIBE 성공보다 먼저 도착(순서 역전)
            handler.handleTextMessage(session, new TextMessage(unsubscribeErrorFor000660));
            handler.handleTextMessage(session, new TextMessage(subscribeSuccessFor005930));

            // Assert — 세이프모드 미진입(UNSUBSCRIBE 오류가 SUBSCRIBE 실패로 오상관되지 않음) + AES 키 정상 저장
            verify(webSocketSafeModeManager, never()).enter(any(), any());
            assertThat(handler.getAesKey("H0STCNT0")).isNotNull();
        }

        @Test
        @DisplayName("AC-8: 상관 키 미발견 시 문자열 폴백 사용 + WARN 로그")
        void keyNotFound_fallsBackToStringHeuristicWithWarnLog() {
            // Arrange — recordPending 미호출(상관 정보 소실 시뮬레이션)

            // Act
            handler.handleTextMessage(session, new TextMessage(UNSUB_ERROR_JSON));

            // Assert — 문자열 휴리스틱으로 UNSUBSCRIBE 분류(카운트 미증가) + 폴백 WARN 로그
            verify(webSocketSafeModeManager, never()).enter(any(), any());
            boolean hasFallbackWarnLog =
                    listAppender.list.stream()
                            .anyMatch(
                                    event ->
                                            event.getLevel() == Level.WARN
                                                    && event.getFormattedMessage()
                                                            .contains("문자열 폴백"));
            assertThat(hasFallbackWarnLog).isTrue();
        }

        @Test
        @DisplayName("AC-9: 재구독 리플레이 경로(recordPending)로 기록된 SUBSCRIBE 성공 응답도 정상 처리된다")
        void resubscribeReplayDirection_subscribeSuccess_registersAesKeyAndResetsCount() {
            // Arrange — resubscribeAll()이 사용할 것과 동일한 경로: 전송 직전 SUBSCRIBE 방향 기록
            handler.recordPending(
                    "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.SUBSCRIBE);
            String subscribeSuccessJson =
                    """
                    {
                      "header": {"tr_id": "H0STCNT0", "tr_key": "005930"},
                      "body": {
                        "rt_cd": "0",
                        "msg1": "SUBSCRIBE SUCCESS",
                        "output": {"iv": "testiv1234567890", "key": "testkey1234567890123456789012345"}
                      }
                    }
                    """;

            // Act
            handler.handleTextMessage(session, new TextMessage(subscribeSuccessJson));

            // Assert
            assertThat(handler.getAesKey("H0STCNT0")).isNotNull();
            verify(webSocketSafeModeManager, never()).enter(any(), any());
        }
    }
}
