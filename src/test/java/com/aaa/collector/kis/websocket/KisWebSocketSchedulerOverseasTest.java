package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisWebSocketScheduler — 해외")
class KisWebSocketSchedulerOverseasTest {

    @Mock private KisWebSocketSessionManager sessionManager;
    @Mock private SubscriptionTargetResolver subscriptionTargetResolver;
    @Mock private UsMarketOpenGate usMarketOpenGate;

    @InjectMocks private KisWebSocketScheduler scheduler;

    // ──────────────────────────────────────────────────────────────────
    // openOverseasSession — 해외 장 개시 (REQ-WSOV-010)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("openOverseasSession — 해외 장 개시")
    class OpenOverseasSession {

        @Test
        @DisplayName("정상 경로: openAll + resolveOverseasSymbols + subscribeOverseasSymbols 호출")
        void normalPath_callsOpenAllAndSubscribe() {
            // Arrange
            List<String> trKeys = List.of("DNASAAPL", "DNYSSPY");
            when(subscriptionTargetResolver.resolveOverseasSymbols()).thenReturn(trKeys);
            when(sessionManager.subscribeOverseasSymbols(trKeys))
                    .thenReturn(new SubscriptionResult(2, 2));

            // Act
            scheduler.openOverseasSession();

            // Assert
            verify(sessionManager).openAll();
            verify(subscriptionTargetResolver).resolveOverseasSymbols();
            verify(sessionManager).subscribeOverseasSymbols(trKeys);
        }

        @Test
        @DisplayName("재진입 방지: overseasRunning=true 시 openAll 미호출")
        void reentryGuard_skipsWhenAlreadyRunning() {
            // Arrange
            scheduler.overseasRunning.set(true);

            // Act
            scheduler.openOverseasSession();

            // Assert
            verify(sessionManager, never()).openAll();
        }

        @Test
        @DisplayName("예외 발생 시 finally에서 overseasRunning=false 복원")
        void exceptionInTry_resetsOverseasRunning() {
            // Arrange
            when(subscriptionTargetResolver.resolveOverseasSymbols())
                    .thenThrow(new RuntimeException("test"));

            // Act
            scheduler.openOverseasSession();

            // Assert
            assertThat(scheduler.overseasRunning.get()).isFalse();
        }

        @Test
        @DisplayName("AC-EMPTY-1: 빈 구독 목록도 정상 완료 — overseasRunning=false 복원")
        void emptyTrKeys_completesNormally() {
            // Arrange
            when(subscriptionTargetResolver.resolveOverseasSymbols()).thenReturn(List.of());
            when(sessionManager.subscribeOverseasSymbols(List.of()))
                    .thenReturn(new SubscriptionResult(0, 0));

            // Act
            scheduler.openOverseasSession();

            // Assert
            verify(sessionManager).subscribeOverseasSymbols(List.of());
            assertThat(scheduler.overseasRunning.get()).isFalse();
        }

        @Test
        @DisplayName("AC-14: 전량 성공(N/N) → INFO 레벨로 \"구독 성공: N/N개\" 기록(해외)")
        void fullSuccess_logsInfoWithSucceededOverAttempted() {
            // Arrange
            Logger schedulerLogger = (Logger) LoggerFactory.getLogger(KisWebSocketScheduler.class);
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();
            schedulerLogger.addAppender(listAppender);
            try {
                List<String> trKeys = List.of("DNASAAPL", "DNYSSPY");
                when(subscriptionTargetResolver.resolveOverseasSymbols()).thenReturn(trKeys);
                when(sessionManager.subscribeOverseasSymbols(trKeys))
                        .thenReturn(new SubscriptionResult(2, 2));

                // Act
                scheduler.openOverseasSession();

                // Assert
                boolean hasInfoLog =
                        listAppender.list.stream()
                                .anyMatch(
                                        event ->
                                                event.getLevel() == Level.INFO
                                                        && event.getFormattedMessage()
                                                                .contains("구독 성공: 2/2개"));
                assertThat(hasInfoLog).isTrue();
            } finally {
                schedulerLogger.detachAppender(listAppender);
                listAppender.stop();
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // closeOverseasSession — 해외 장 종료 (REQ-WSOV-011/-040a)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("closeOverseasSession — 해외 장 종료")
    class CloseOverseasSession {

        @Test
        @DisplayName("closeAll 호출")
        void callsCloseAll() {
            // Act
            scheduler.closeOverseasSession();

            // Assert
            verify(sessionManager).closeAll();
        }

        @Test
        @DisplayName("AC-CLOSE-1: 예외 발생 시 전파 없음 — overseasRunning 변경 없음")
        void exceptionIsolated_overseasRunningUnchanged() {
            // Arrange
            doThrow(new RuntimeException("test")).when(sessionManager).closeAll();
            boolean runningBefore = scheduler.overseasRunning.get();

            // Act — 예외 전파 없음
            assertThatCode(scheduler::closeOverseasSession).doesNotThrowAnyException();

            // Assert — 종료 경로는 overseasRunning 플래그 미사용
            assertThat(scheduler.overseasRunning.get()).isEqualTo(runningBefore);
        }
    }
}
