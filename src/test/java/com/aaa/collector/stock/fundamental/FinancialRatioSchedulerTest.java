package com.aaa.collector.stock.fundamental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@DisplayName("FinancialRatioScheduler 단위 테스트 (AC-SCHED-1/3/4)")
class FinancialRatioSchedulerTest {

    @Mock private FinancialRatioCollectionService collectionService;

    @InjectMocks private FinancialRatioScheduler scheduler;

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH4-001/003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 8 * * SAT', zone='Asia/Seoul'")
        void hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = FinancialRatioScheduler.class.getMethod("collectFinancialRatio");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 8 * * SAT");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (ADR-008)")
        void noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = FinancialRatioScheduler.class.getMethod("collectFinancialRatio");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectFinancialRatio — 예외 격리 (AC-SCHED-4)")
    class ExceptionIsolation {

        @Test
        @DisplayName("정상 흐름 — collect 1회 호출")
        void normalFlow() {
            when(collectionService.collect()).thenReturn(new FundamentalResult(10, 10, 0));

            scheduler.collectFinancialRatio();

            verify(collectionService).collect();
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 스레드로 전파되지 않음")
        void exceptionAbsorbed() {
            when(collectionService.collect()).thenThrow(new RuntimeException("수집 중 예외"));

            assertThatCode(scheduler::collectFinancialRatio).doesNotThrowAnyException();
        }
    }
}
