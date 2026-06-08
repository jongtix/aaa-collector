package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.common.safemode.SafeModeManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    }

    // ──────────────────────────────────────────────────────────────────
    // Type B — 구독 실패 (rt_cd≠0) 반복
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Type B — 구독 실패 응답 (rt_cd≠0)")
    class TypeBSubscribeFailure {

        private final String failureJson =
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
            for (int i = 0; i < 4; i++) {
                handler.handleTextMessage(session, new TextMessage(failureJson));
            }

            verify(webSocketSafeModeManager, never()).enter(any(), any());
        }

        @Test
        @DisplayName("5회 실패 — 안전 모드 진입")
        void fiveFailures_entersSafeMode() {
            for (int i = 0; i < 5; i++) {
                handler.handleTextMessage(session, new TextMessage(failureJson));
            }

            verify(webSocketSafeModeManager, times(1)).enter(any(), any());
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
}
