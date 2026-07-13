package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.common.retry.ExponentialBackoff;
import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import java.net.URI;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import org.slf4j.LoggerFactory;
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
        @DisplayName("subscribe → activeSubscriptions에 추가, TextMessage 전송, true 반환")
        void shouldAddToActiveSubscriptionsAndSendMessage() throws Exception {
            // Act
            boolean result = session.subscribe("H0STCNT0", "005930");

            // Assert
            assertThat(result).isTrue();
            assertThat(session.getSubscriptionCount()).isEqualTo(1);
            verify(rawSession, atLeastOnce()).sendMessage(any(TextMessage.class));
        }

        @Test
        @DisplayName("전송 실패(세션 닫힘) → subscribe가 false 반환, activeSubscriptions 미추가 (AC-16)")
        void shouldReturnFalseAndNotTrackWhenSendFails() {
            // Arrange — 세션이 열려 있지 않아 전송이 생략되는 상황을 시뮬레이션
            when(rawSession.isOpen()).thenReturn(false);

            // Act
            boolean result = session.subscribe("H0STCNT0", "005930");

            // Assert — 현재 코드는 전송 실패와 무관하게 무조건 추가하여 실패를 성공으로 오계수함(회귀 차단)
            assertThat(result).isFalse();
            assertThat(session.getSubscriptionCount()).isEqualTo(0);
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
    // 구독 해제(단건)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("구독 해제(unsubscribe)")
    class Unsubscribe {

        @Test
        @DisplayName("전송 실패 → unsubscribe가 false 반환, activeSubscriptions에서 미제거")
        void shouldReturnFalseAndKeepTrackingWhenSendFails() throws Exception {
            // Arrange — 정상 전송으로 먼저 구독을 등록한 뒤, 해제 시 전송 실패를 시뮬레이션
            session.subscribe("H0STCNT0", "005930");
            assertThat(session.getSubscriptionCount()).isEqualTo(1);
            when(rawSession.isOpen()).thenReturn(false);

            // Act
            boolean result = session.unsubscribe("H0STCNT0", "005930");

            // Assert — 전송 실패와 무관하게 무조건 제거하면 activeSubscriptions가 실제 KIS측 구독 상태와
            // 어긋나 재연결 시 resubscribeAll이 해당 종목을 재구독하지 못함(회귀 차단)
            assertThat(result).isFalse();
            assertThat(session.getSubscriptionCount()).isEqualTo(1);
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

    // ──────────────────────────────────────────────────────────────────
    // 재연결 성공 시 재구독 리플레이 (REQ-WSRES-001~004)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("재구독 리플레이 (재연결 성공 시)")
    class ResubscribeReplay {

        private void arrangeReconnectSuccess() throws InterruptedException, ExecutionException {
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(handshakeFuture);
            when(handshakeFuture.get()).thenReturn(rawSession);
        }

        @Test
        @DisplayName("AC-1: 재연결 성공 → 끊김 전 활성 구독 전체가 SUBSCRIBE로 재전송된다")
        void reconnectSuccess_resendsSubscribeForAllActiveSubscriptions() throws Exception {
            // Arrange — 2건 구독 중(H0STCNT0, H0STASP0)
            session.subscribe("H0STCNT0", "005930");
            session.subscribe("H0STASP0", "005930");
            arrangeReconnectSuccess();
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

            // Act
            session.handleDisconnect(marketOpen);

            // Assert — SUBSCRIBE(tr_type=1) 총 4건(초기 2건 + 재구독 2건)
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(rawSession, atLeastOnce()).sendMessage(captor.capture());
            long subscribeCount =
                    captor.getAllValues().stream()
                            .filter(msg -> msg.getPayload().contains("\"tr_type\":\"1\""))
                            .count();
            assertThat(subscribeCount).isEqualTo(4);
        }

        @Test
        @DisplayName("AC-2: 재구독 리플레이는 종목 선정 로직(SubscriptionTargetResolver)을 전혀 참조하지 않는다")
        void resubscribeReplay_neverInteractsWithSubscriptionTargetResolver() throws Exception {
            // Arrange — KisWebSocketSession은 애초에 SubscriptionTargetResolver에 대한 참조를 갖지 않는다.
            // 이 스파이는 재구독 리플레이가 매니저 레벨 재분배를 재사용하지 않음(REQ-WSRES-002)을 문서화하기 위한 것이다.
            SubscriptionTargetResolver resolverSpy = mock(SubscriptionTargetResolver.class);
            session.subscribe("H0STCNT0", "005930");
            arrangeReconnectSuccess();
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

            // Act
            session.handleDisconnect(marketOpen);

            // Assert
            verifyNoInteractions(resolverSpy);
        }

        @Test
        @DisplayName("AC-3: 재연결 실패 시 재구독을 시도하지 않는다")
        void reconnectFailure_doesNotResubscribe() throws Exception {
            // Arrange
            session.subscribe("H0STCNT0", "005930");
            when(marketSchedule.isDomesticOpen(any())).thenReturn(true);
            when(marketSchedule.isOverseasOpen(any())).thenReturn(false);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenThrow(new RuntimeException("연결 실패"));
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

            // Act
            session.handleDisconnect(marketOpen);

            // Assert — 초기 구독 1건만 전송, 재구독 없음
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(rawSession, atLeastOnce()).sendMessage(captor.capture());
            long subscribeCount =
                    captor.getAllValues().stream()
                            .filter(msg -> msg.getPayload().contains("\"tr_type\":\"1\""))
                            .count();
            assertThat(subscribeCount).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-4: 재구독 완료 시 건수 포함 + \"데이터 갭 발생 가능\" WARN 로그")
        void reconnectSuccess_logsResubscribeCountWithDataGapWarning() throws Exception {
            // Arrange — 로그 캡처
            Logger sessionLogger = (Logger) LoggerFactory.getLogger(KisWebSocketSession.class);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            sessionLogger.addAppender(listAppender);
            try {
                session.subscribe("H0STCNT0", "005930");
                session.subscribe("H0STASP0", "005930");
                arrangeReconnectSuccess();
                ZonedDateTime marketOpen =
                        ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

                // Act
                session.handleDisconnect(marketOpen);

                // Assert — 건수(2건) + "데이터 갭 발생 가능" 문구를 포함한 WARN 로그
                boolean hasExpectedLog =
                        listAppender.list.stream()
                                .anyMatch(
                                        event ->
                                                event.getLevel() == Level.WARN
                                                        && event.getFormattedMessage()
                                                                .contains("데이터 갭 발생 가능")
                                                        && event.getFormattedMessage()
                                                                .contains("2건"));
                assertThat(hasExpectedLog).isTrue();
            } finally {
                sessionLogger.detachAppender(listAppender);
                listAppender.stop();
            }
        }

        @Test
        @DisplayName("AC-9: 재구독 리플레이도 방향 기록(recordPending)을 거쳐 신규 구독과 동일한 상관 경로에 편입된다")
        void resubscribeReplay_recordsSubscribeDirectionForEachKey() throws Exception {
            // Arrange
            session.subscribe("H0STCNT0", "005930");
            session.subscribe("H0STASP0", "005930");
            arrangeReconnectSuccess();
            ZonedDateTime marketOpen =
                    ZonedDateTime.of(2025, 1, 6, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));

            // Act
            session.handleDisconnect(marketOpen);

            // Assert — 초기 구독 2건 + 재구독 2건 = recordPending(SUBSCRIBE) 총 4회
            verify(messageHandler, times(4))
                    .recordPending(
                            any(String.class),
                            any(String.class),
                            org.mockito.ArgumentMatchers.eq(
                                    KisWebSocketMessageHandler.Direction.SUBSCRIBE));
        }
    }
}
