package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisWebSocketScheduler")
class KisWebSocketSchedulerTest {

    @Mock private KisWebSocketSessionManager sessionManager;
    @Mock private SubscriptionTargetResolver subscriptionTargetResolver;

    @InjectMocks private KisWebSocketScheduler scheduler;

    // ──────────────────────────────────────────────────────────────────
    // 국내 장 개시
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("openDomesticSession — 국내 장 개시")
    class OpenDomesticSession {

        @Test
        @DisplayName("openDomesticSession 호출 시 openAll과 subscribeSymbols가 실행된다")
        void shouldCallOpenAllAndSubscribeSymbols() {
            // Arrange
            List<String> symbols = List.of("005930", "000660");
            when(subscriptionTargetResolver.resolveDomesticSymbols()).thenReturn(symbols);

            // Act
            scheduler.openDomesticSession();

            // Assert
            verify(sessionManager).openAll();
            verify(sessionManager).subscribeSymbols(symbols);
        }

        @Test
        @DisplayName("domesticRunning=true 상태에서 호출 시 두 번째 호출은 건너뛴다 (재진입 방지)")
        void shouldSkipWhenAlreadyRunning() {
            // Arrange — 이미 실행 중 상태로 설정
            scheduler.domesticRunning.set(true);

            // Act
            scheduler.openDomesticSession();

            // Assert — sessionManager 메서드는 호출되지 않아야 함
            verify(sessionManager, never()).openAll();
            verify(sessionManager, never())
                    .subscribeSymbols(org.mockito.ArgumentMatchers.anyList());

            // 정리 — 다른 테스트에 영향을 주지 않도록 복원
            scheduler.domesticRunning.set(false);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 국내 장 종료
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("closeDomesticSession — 국내 장 종료")
    class CloseDomesticSession {

        @Test
        @DisplayName("closeDomesticSession 호출 시 closeAll이 실행된다")
        void shouldCallCloseAll() {
            scheduler.closeDomesticSession();

            verify(sessionManager).closeAll();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // @Scheduled cron 어노테이션 검증 (fixedDelay 금지)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("@Scheduled 어노테이션 — cron 전용, fixedDelay 금지")
    class ScheduledAnnotationValidation {

        @Test
        @DisplayName("모든 @Scheduled 메서드는 cron만 가지며 fixedDelay가 0이다")
        void allScheduledMethodsHaveCronOnlyAndNoFixedDelay() throws NoSuchMethodException {
            // Arrange
            String[] methodNames = {
                "openDomesticSession", "closeDomesticSession",
                "openOverseasSession", "closeOverseasSession"
            };

            for (String methodName : methodNames) {
                Method method = KisWebSocketScheduler.class.getMethod(methodName);
                Scheduled scheduled = method.getAnnotation(Scheduled.class);

                // Assert — cron이 존재하고 비어 있지 않음
                assertThat(scheduled).as("메서드 %s에 @Scheduled 없음", methodName).isNotNull();
                assertThat(scheduled.cron()).as("메서드 %s의 cron이 비어 있음", methodName).isNotBlank();

                // Assert — fixedDelay가 기본값(-1) 또는 0 (설정되지 않음)
                assertThat(scheduled.fixedDelay())
                        .as("메서드 %s에 fixedDelay가 설정됨 (CLAUDE.md 금지 규칙 위반)", methodName)
                        .isLessThanOrEqualTo(0);
                assertThat(scheduled.fixedDelayString())
                        .as("메서드 %s에 fixedDelayString이 설정됨 (CLAUDE.md 금지 규칙 위반)", methodName)
                        .isEmpty();
            }
        }
    }
}
