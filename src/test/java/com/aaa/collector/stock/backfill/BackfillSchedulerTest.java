package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

@DisplayName("BackfillScheduler 단위 테스트")
class BackfillSchedulerTest {

    @Nested
    @DisplayName("run() 위임")
    class Delegation {

        @Test
        @DisplayName("run() — orchestrator.run()을 1회 위임한다")
        void run_delegatesToOrchestrator() {
            BackfillOrchestrator orchestrator = mock(BackfillOrchestrator.class);
            BackfillScheduler scheduler = new BackfillScheduler(orchestrator);

            scheduler.run();

            verify(orchestrator).run();
        }

        @Test
        @DisplayName("run() — orchestrator.run()이 RuntimeException을 던져도 전파하지 않는다 (스케줄러 스레드 보호)")
        void run_absorbsExceptionFromOrchestrator() {
            BackfillOrchestrator orchestrator = mock(BackfillOrchestrator.class);
            doThrow(new RuntimeException("cron 오류 시뮬레이션")).when(orchestrator).run();
            BackfillScheduler scheduler = new BackfillScheduler(orchestrator);

            assertThatCode(scheduler::run).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("@Scheduled 어노테이션 검증")
    class ScheduledAnnotation {

        @Test
        @DisplayName("run() 메서드에 @Scheduled(cron=..., zone='Asia/Seoul')이 선언되어 있다")
        void run_scheduledAnnotation_hasCronAndKstZone() throws NoSuchMethodException {
            Method runMethod = BackfillScheduler.class.getMethod("run");
            Scheduled scheduled = runMethod.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("${aaa.backfill.cron:0 0 2 * * *}");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }
    }
}
