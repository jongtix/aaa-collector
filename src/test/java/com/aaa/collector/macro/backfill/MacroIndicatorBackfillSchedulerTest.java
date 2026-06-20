package com.aaa.collector.macro.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T6 RED — MacroIndicatorBackfillScheduler 단위 테스트.
 *
 * <p>cron 어노테이션, 예외 흡수 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MacroIndicatorBackfillScheduler — 단위 테스트")
class MacroIndicatorBackfillSchedulerTest {

    @Mock private MacroIndicatorBackfillOrchestrator orchestrator;

    @InjectMocks private MacroIndicatorBackfillScheduler scheduler;

    @Nested
    @DisplayName("@Scheduled 어노테이션")
    class ScheduledAnnotation {

        @Test
        @DisplayName("run 메서드에 cron='${aaa.macro.backfill.cron:0 0 3 * * *}', zone='Asia/Seoul' 존재")
        void run_hasCorrectCronAnnotation() throws Exception {
            Method method = MacroIndicatorBackfillScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("${aaa.macro.backfill.cron:0 0 3 * * *}");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }
    }

    @Nested
    @DisplayName("예외 흡수")
    class ExceptionAbsorption {

        @Test
        @DisplayName("orchestrator.run() 정상 — orchestrator.run() 호출됨")
        void run_delegatesToOrchestrator() {
            scheduler.run();
            verify(orchestrator).run();
        }

        @Test
        @DisplayName("orchestrator.run() 예외 — 예외 전파 없이 종료")
        void orchestratorException_noExceptionPropagated() {
            doThrow(new RuntimeException("DB error")).when(orchestrator).run();
            assertThatCode(scheduler::run).doesNotThrowAnyException();
        }
    }
}
