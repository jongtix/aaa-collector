package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KisWebSocketSession")
class KisWebSocketSessionTest {

    private static final String ALIAS = "test-alias";
    private static final String APPROVAL_KEY = "test-approval-key";
    private static final String WS_URL = "ws://ops.koreainvestment.com:21000";

    @Mock private WebSocketClient webSocketClient;
    @Mock private KisWebSocketMessageHandler messageHandler;
    @Mock private KisMarketSchedule marketSchedule;
    @Mock private SafeModeManager webSocketSafeModeManager;
    @Mock private Sleeper sleeper;
    @Mock private Clock clock;
    @Mock private WebSocketSession rawSession;
    @Mock private CompletableFuture<WebSocketSession> handshakeFuture;

    private KisWebSocketSession session;

    @BeforeEach
    @SuppressWarnings("PMD.SignatureDeclareThrowsException")
    void setUp() throws Exception {
        when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenReturn(handshakeFuture);
        when(handshakeFuture.get()).thenReturn(rawSession);
        when(rawSession.isOpen()).thenReturn(true);

        session =
                new KisWebSocketSession(
                        ALIAS,
                        APPROVAL_KEY,
                        webSocketClient,
                        messageHandler,
                        marketSchedule,
                        webSocketSafeModeManager,
                        sleeper,
                        clock);
        session.connect(WS_URL);
    }

    // ──────────────────────────────────────────────────────────────────
    // 재연결 — 장중
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("장중 연결 끊김")
    class InHoursDisconnect {

        @Test
        @DisplayName("장중 disconnect → 재연결 시도, Sleeper.sleep() 호출 (REQ-WS-021)")
        void shouldAttemptReconnectDurringMarketHours() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);
            when(handshakeFuture.get()).thenReturn(rawSession);

            // Act
            session.handleDisconnect(marketOpen);

            // Assert
            verify(sleeper, atLeastOnce()).sleep(any(Long.class));
        }

        @Test
        @DisplayName("장중 disconnect → webSocketClient.execute() 재호출")
        void shouldCallConnectAgainDuringMarketHours() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);
            when(handshakeFuture.get()).thenReturn(rawSession);

            // Act
            session.handleDisconnect(marketOpen);

            // Assert — setUp()에서 1회 + handleDisconnect에서 1회 = 2회
            verify(webSocketClient, times(2))
                    .execute(any(), any(WebSocketHttpHeaders.class), any(URI.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 재연결 — 장외
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("장외 연결 끊김")
    class OutOfHoursDisconnect {

        @Test
        @DisplayName("장외 disconnect → 재연결 시도 없음, Sleeper 미호출 (REQ-WS-021)")
        void shouldNotReconnectOutsideMarketHours() throws Exception {
            // Arrange
            ZonedDateTime marketClosed =
                    ZonedDateTime.of(2025, 1, 6, 3, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(false);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);

            // Act
            session.handleDisconnect(marketClosed);

            // Assert
            verify(sleeper, never()).sleep(any(Long.class));
            // setUp()에서만 호출, handleDisconnect에서 추가 호출 없음
            verify(webSocketClient, times(1))
                    .execute(any(), any(WebSocketHttpHeaders.class), any(URI.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 5회 연속 재연결 실패 → 안전 모드 (REQ-WS-022)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("연속 재연결 실패")
    class ConsecutiveReconnectFailures {

        @Test
        @DisplayName("5회 연속 재연결 실패 → SafeModeManager.enter() 호출 (REQ-WS-022)")
        void shouldEnterSafeModeAfterFiveConsecutiveFailures() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            // 재연결 시도 시 항상 예외 발생
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenThrow(new RuntimeException("연결 실패"));

            // Act — 5회 disconnect
            for (int i = 0; i < 5; i++) {
                session.handleDisconnect(marketOpen);
            }

            // Assert
            verify(webSocketSafeModeManager, times(1))
                    .enter(any(String.class), any(Throwable.class));
        }

        @Test
        @DisplayName("4회 연속 실패 → 아직 안전 모드 진입 없음")
        void shouldNotEnterSafeModeBeforeFiveFailures() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenThrow(new RuntimeException("연결 실패"));

            // Act
            for (int i = 0; i < 4; i++) {
                session.handleDisconnect(marketOpen);
            }

            // Assert
            verify(webSocketSafeModeManager, never())
                    .enter(any(String.class), any(Throwable.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 정상 종료 후 재연결 차단
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 종료(close)")
    class GracefulClose {

        @Test
        @DisplayName("close() 후 handleDisconnect → 재연결 시도 없음")
        void shouldNotReconnectAfterGracefulClose() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

            // Act
            session.close();
            session.handleDisconnect(marketOpen);

            // Assert
            verify(sleeper, never()).sleep(any(Long.class));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 백오프 지연 시퀀스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("백오프 지연 시퀀스")
    class BackoffSequence {

        @Test
        @DisplayName("attempt 0 → 1000ms, attempt 1 → 2000ms 지연 (ExponentialBackoff 위임)")
        void shouldApplyCorrectBackoffDelays() throws Exception {
            // Arrange
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenThrow(new RuntimeException("실패"));

            // Act — 2회 disconnect
            session.handleDisconnect(marketOpen);
            session.handleDisconnect(marketOpen);

            // Assert
            ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
            verify(sleeper, times(2)).sleep(delayCaptor.capture());
            assertThat(delayCaptor.getAllValues().getFirst())
                    .isEqualTo(ExponentialBackoff.delay(0, 1000).toMillis()); // 1000ms
            assertThat(delayCaptor.getAllValues().get(1))
                    .isEqualTo(ExponentialBackoff.delay(1, 1000).toMillis()); // 2000ms
        }

        @Test
        @DisplayName("attempt 6 → 60000ms (60초 캡 적용)")
        void shouldCapDelayAt60Seconds() {
            // 직접 ExponentialBackoff 검증
            assertThat(ExponentialBackoff.delay(6, 1000).toMillis()).isEqualTo(60_000L);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 구독
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("구독(subscribe)")
    class Subscribe {

        @Test
        @DisplayName("subscribe → activeSubscriptions에 추가, TextMessage 전송")
        void shouldAddToActiveSubscriptionsAndSendMessage() throws Exception {
            // Act
            session.subscribe("H0STCNT0", "005930");

            // Assert
            assertThat(session.getSubscriptionCount()).isEqualTo(1);
            verify(rawSession, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("subscribe → JSON에 approval_key, tr_id, tr_key 포함")
        void shouldSendSubscribeMessageWithCorrectJson() throws Exception {
            // Act
            session.subscribe("H0STCNT0", "005930");

            // Assert
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(rawSession, atLeastOnce()).sendMessage(captor.capture());
            String payload = captor.getValue().getPayload();
            assertThat(payload).contains(APPROVAL_KEY);
            assertThat(payload).contains("H0STCNT0");
            assertThat(payload).contains("005930");
            assertThat(payload).contains("\"tr_type\":\"1\"");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 전체 구독 해제
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("전체 구독 해제(unsubscribeAll)")
    class UnsubscribeAll {

        @Test
        @DisplayName("unsubscribeAll → 각 구독에 대해 unsubscribe 메시지 전송, activeSubscriptions 비움")
        void shouldSendUnsubscribeForEachAndClearSet() throws Exception {
            // Arrange
            session.subscribe("H0STCNT0", "005930");
            session.subscribe("H0STASP0", "005930");
            // subscribe 호출 시 sendMessage 호출 2회

            // Act
            session.unsubscribeAll();

            // Assert — subscribe 2회 + unsubscribe 2회 = 4회
            verify(rawSession, times(4)).sendMessage(any(TextMessage.class));
            assertThat(session.getSubscriptionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("unsubscribeAll → tr_type=2인 메시지 포함")
        void shouldSendMessagesWithTrType2() throws Exception {
            // Arrange
            session.subscribe("H0STCNT0", "005930");

            // Act
            session.unsubscribeAll();

            // Assert
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(rawSession, atLeastOnce()).sendMessage(captor.capture());
            boolean hasUnsubscribeMsg =
                    captor.getAllValues().stream()
                            .anyMatch(msg -> msg.getPayload().contains("\"tr_type\":\"2\""));
            assertThat(hasUnsubscribeMsg).isTrue();
        }
    }
}
