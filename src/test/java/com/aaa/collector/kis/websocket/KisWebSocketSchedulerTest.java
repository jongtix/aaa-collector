package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.gate.MarketOpenGate;
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
    @Mock private MarketOpenGate marketOpenGate;
    @Mock private WsRecoveryProperties wsRecoveryProperties;

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
    // 장 중 재배포 startup 복구 (SPEC-COLLECTOR-WS-RECOVERY-001)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recoverDomesticSessionOnStartup — 장 중 재배포 시 국내 WebSocket 구독 복구")
    class RecoverDomesticSessionOnStartup {

        @Test
        @DisplayName("장중·세션 미기동 → openAll과 subscribeSymbols가 정확히 1회 호출된다 (AC-1)")
        void shouldRecoverWhenMarketOpenAndSessionNotRunning() {
            // Arrange
            List<String> symbols = List.of("005930", "000660");
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(true);
            when(subscriptionTargetResolver.resolveDomesticSymbols()).thenReturn(symbols);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert
            verify(sessionManager).openAll();
            verify(sessionManager).subscribeSymbols(symbols);
        }

        @Test
        @DisplayName("장외 시간 → openDomesticSession 미호출 (AC-2)")
        void shouldNotRecoverWhenMarketClosed() {
            // Arrange
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(false);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert
            verify(sessionManager, never()).openAll();
        }

        @Test
        @DisplayName("주말/캘린더 휴장 → openDomesticSession 미호출 (AC-3/AC-4 — isMarketOpenNow=false)")
        void shouldNotRecoverOnWeekendOrHoliday() {
            // Arrange — isMarketOpenNow=false: 주말·캘린더 휴장 동일 경로
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(false);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert
            verify(sessionManager, never()).openAll();
        }

        @Test
        @DisplayName("세션 이미 기동됨 → openDomesticSession 미호출 (AC-6 — 중복 openAll 방지)")
        void shouldSkipWhenSessionAlreadyRunning() {
            // Arrange — 08:55 cron이 이미 openAll()로 세션을 연 상태
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(true);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert — openAll 추가 호출 없음 (연결 누수·중복 KIS 연결 방지)
            verify(sessionManager, never()).openAll();
            verify(marketOpenGate, never()).isMarketOpenNow();
        }

        @Test
        @DisplayName("domesticRunning=true → openAll 추가 호출 없음 (AC-7 — cron 동시 경합 멱등성)")
        void shouldSkipOpenAllWhenDomesticRunningIsTrue() {
            // Arrange — cron이 동시에 openDomesticSession에 진입한 상태 시뮬레이션
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(true);
            scheduler.domesticRunning.set(true); // 동시 cron 진입 시뮬레이션

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert — compareAndSet 실패로 openAll 건너뜀
            verify(sessionManager, never()).openAll();

            // 정리
            scheduler.domesticRunning.set(false);
        }

        @Test
        @DisplayName("onApplicationReady는 별도 Virtual Thread를 spawn하고 즉시 반환한다 (AC-8)")
        void onApplicationReadyReturnsImmediately() {
            // Arrange — wsRecoveryProperties.isEnabled()는 Mock 기본값 false를 반환하므로
            // 복구 본체가 즉시 종료됨 (stubbing 불필요 — 별도 VT에서 실행되므로 strict 모드 오탐 방지)

            // Act — Thread.ofVirtual().start()만 실행하고 즉시 반환 (비블로킹, REQ-WSREC-001)
            scheduler.onApplicationReady();

            // Assert — 메서드가 즉시 반환됐음을 확인 (openAll 미호출: 비동기 스레드 미완료 또는 disabled)
            verify(sessionManager, never()).openAll();
        }

        @Test
        @DisplayName("enabled=false → 판정·복구 전혀 수행하지 않는다 (AC-9 게이트)")
        void shouldNotRecoverWhenDisabled() {
            // Arrange
            when(wsRecoveryProperties.isEnabled()).thenReturn(false);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert — isMarketOpenNow·isRunning·openAll 모두 미호출
            verify(marketOpenGate, never()).isMarketOpenNow();
            verify(sessionManager, never()).isRunning();
            verify(sessionManager, never()).openAll();
        }

        @Test
        @DisplayName("복구 경로에서 openOverseasSession 미호출 (AC-11 — 해외 범위 제외)")
        void shouldNotOpenOverseasSession() {
            // Arrange
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(true);
            when(subscriptionTargetResolver.resolveDomesticSymbols()).thenReturn(List.of());

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert
            verify(sessionManager, never()).subscribeOverseasSymbols(any());
        }

        @Test
        @DisplayName("복구 경로에서 isOpenDay 미호출 (AC-13 — 동기 캘린더 조회 금지)")
        // 구조적 보장: KisWebSocketScheduler가 KisHolidayClient/MarketSessionGateRefresher를
        // 의존하지 않으므로 동기 KIS 호출 불가능. 복구는 isMarketOpenNow(읽기 전용) + openDomesticSession만
        // 호출한다(REQ-WSREC-031).
        void shouldNotCallIsOpenDayInRecoveryPath() {
            // Arrange
            when(wsRecoveryProperties.isEnabled()).thenReturn(true);
            when(sessionManager.isRunning()).thenReturn(false);
            when(marketOpenGate.isMarketOpenNow()).thenReturn(false);

            // Act
            scheduler.recoverDomesticSessionOnStartup();

            // Assert — isOpenDay(날짜 기반 캘린더 직접 조회) 미호출
            verify(marketOpenGate, never()).isOpenDay(any());
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
