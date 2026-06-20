package com.aaa.collector.market.backfill;

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

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketIndicatorBackfillScheduler 단위 테스트")
class MarketIndicatorBackfillSchedulerTest {

    @Mock private MarketIndicatorBackfillOrchestrator orchestrator;

    @InjectMocks private MarketIndicatorBackfillScheduler scheduler;

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-046)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("backfillMarketIndicators — cron 기반, zone='Asia/Seoul'")
        void hasCorrectCronAnnotation() throws NoSuchMethodException {
            Method method =
                    MarketIndicatorBackfillScheduler.class.getMethod("backfillMarketIndicators");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isNotEmpty(); // SpEL 표현식
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 (REQ-046)")
        void noFixedDelayOrRate() throws NoSuchMethodException {
            Method method =
                    MarketIndicatorBackfillScheduler.class.getMethod("backfillMarketIndicators");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("실행 흐름")
    class ExecutionFlow {

        @Test
        @DisplayName("시딩 후 백필 실행 순서")
        void seedThenRunBackfill() {
            scheduler.backfillMarketIndicators();

            verify(orchestrator).seed();
            verify(orchestrator).runBackfill();
        }

        @Test
        @DisplayName("예외 발생 시 스케줄러 스레드로 전파 안 됨")
        void exception_notPropagated() {
            doThrow(new RuntimeException("백필 실패")).when(orchestrator).runBackfill();

            assertThatCode(scheduler::backfillMarketIndicators).doesNotThrowAnyException();
        }
    }
}
