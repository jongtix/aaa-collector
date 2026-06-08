package com.aaa.collector.stock.etf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("EtfRepresentativeScheduler 단위 테스트")
class EtfRepresentativeSchedulerTest {

    @Mock private EtfRepresentativeService etfRepresentativeService;

    @InjectMocks private EtfRepresentativeScheduler scheduler;

    @Test
    @DisplayName("recalculateWeekly cron은 '0 50 7 * * MON', zone은 'Asia/Seoul'")
    void recalculateWeekly_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
        Method method = EtfRepresentativeScheduler.class.getMethod("recalculateWeekly");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 50 7 * * MON");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @Nested
    @DisplayName("중복 실행 가드 (REQ-ETFSCHED-002)")
    class DuplicateRunGuard {

        @Test
        @DisplayName("첫 번째 실행은 recalculate()를 호출한다")
        void recalculateWeekly_firstInvocation_callsRecalculate() {
            scheduler.recalculateWeekly();

            verify(etfRepresentativeService).recalculate();
        }

        @Test
        @DisplayName("이전 실행 중이면 recalculate()를 호출하지 않는다")
        void recalculateWeekly_concurrentInvocation_skipsRecalculate() {
            // Arrange: force running=true to simulate an in-progress run (package-private field)
            scheduler.running.set(true);

            // Act
            scheduler.recalculateWeekly();

            // Assert: service not called due to guard
            verify(etfRepresentativeService, never()).recalculate();

            // Cleanup
            scheduler.running.set(false);
        }

        @Test
        @DisplayName("예외 발생 후에도 running 플래그가 false로 복원된다")
        void recalculateWeekly_exceptionDuringRun_resetsRunningFlag() {
            // Arrange
            Mockito.doThrow(new RuntimeException("simulated failure"))
                    .when(etfRepresentativeService)
                    .recalculate();

            // Act
            scheduler.recalculateWeekly();

            // Assert: running flag reset to false after exception (package-private field)
            assertThat(scheduler.running.get()).isFalse();
        }
    }
}
