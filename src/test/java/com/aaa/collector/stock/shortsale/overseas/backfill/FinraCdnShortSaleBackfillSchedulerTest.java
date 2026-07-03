package com.aaa.collector.stock.shortsale.overseas.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * FinraCdnShortSaleBackfillScheduler 단위 테스트 (SPEC-COLLECTOR-BACKFILL-008 T6, AC-BF-22/-23).
 *
 * <p>{@code DartDisclosureBackfillScheduler}(cron + {@code AtomicBoolean} single-flight) 구조를 미러링한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinraCdnShortSaleBackfillScheduler")
class FinraCdnShortSaleBackfillSchedulerTest {

    @Mock private FinraCdnShortSaleBackfillOrchestrator orchestrator;

    @InjectMocks private FinraCdnShortSaleBackfillScheduler scheduler;

    @Nested
    @DisplayName("@Scheduled cron 전용 (AC-BF-23)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("run 메서드에 전용 cron 프로퍼티(기본 0 0 5 * * *, Asia/Seoul) 존재")
        void run_hasCronAnnotationWithDefaults() throws NoSuchMethodException {
            Method method = FinraCdnShortSaleBackfillScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron())
                    .isEqualTo("${aaa.shortsale-overseas.backfill.cron:0 0 5 * * *}");
            assertThat(scheduled.zone())
                    .isEqualTo("${aaa.shortsale-overseas.backfill.zone:Asia/Seoul}");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate는 사용하지 않는다(cron 전용 정적 확인)")
        void run_neverUsesFixedDelayOrFixedRate() throws NoSuchMethodException {
            Method method = FinraCdnShortSaleBackfillScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRate()).isEqualTo(-1);
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("오케스트레이터 위임 + 예외 흡수")
    class DelegationAndExceptionAbsorption {

        @Test
        @DisplayName("cron 발화 시 orchestrator.run()을 1회 호출한다")
        void run_delegatesToOrchestratorOnce() {
            scheduler.run();

            verify(orchestrator).run();
        }

        @Test
        @DisplayName("orchestrator.run() 예외 — 스케줄러 스레드로 전파되지 않는다")
        void orchestratorException_doesNotPropagate() {
            doThrow(new RuntimeException("CDN error")).when(orchestrator).run();

            assertThatCode(scheduler::run).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AtomicBoolean single-flight 가드 (독립성, AC-BF-22)")
    class SingleFlightGuard {

        @Test
        @DisplayName("실행 중(running=true) 재진입 시 오케스트레이터를 다시 호출하지 않는다")
        void reentrantCallWhileRunning_skipsOrchestratorInvocation() {
            AtomicInteger orchestratorCallCount = new AtomicInteger();
            doAnswer(
                            inv -> {
                                orchestratorCallCount.incrementAndGet();
                                // 실행 중 재진입 시뮬레이션 — running=true 상태에서 스케줄러가 다시 발화됨
                                scheduler.run();
                                return null;
                            })
                    .when(orchestrator)
                    .run();

            scheduler.run();

            assertThat(orchestratorCallCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("예외 발생 후에도 running 플래그가 해제되어 다음 실행이 정상 동작한다")
        void afterException_flagResetAllowsNextRun() {
            doThrow(new RuntimeException("transient")).when(orchestrator).run();
            scheduler.run();

            org.mockito.Mockito.reset(orchestrator);
            scheduler.run();

            verify(orchestrator).run();
        }
    }
}
