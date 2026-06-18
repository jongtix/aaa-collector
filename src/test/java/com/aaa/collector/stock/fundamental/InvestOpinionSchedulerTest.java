package com.aaa.collector.stock.fundamental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestOpinionScheduler 단위 테스트 (AC-SCHED-2/3/4)")
class InvestOpinionSchedulerTest {

    @Mock private InvestOpinionCollectionService collectionService;

    @InjectMocks private InvestOpinionScheduler scheduler;

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH4-002/003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 18 * * MON-FRI', zone='Asia/Seoul'")
        void hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = InvestOpinionScheduler.class.getMethod("collectInvestOpinion");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 18 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (ADR-008)")
        void noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = InvestOpinionScheduler.class.getMethod("collectInvestOpinion");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectInvestOpinion — 예외 격리 (AC-SCHED-4)")
    class ExceptionIsolation {

        @Test
        @DisplayName("정상 흐름 — collect 1회 호출")
        void normalFlow() {
            when(collectionService.collect(any(LocalDate.class)))
                    .thenReturn(new FundamentalResult(10, 10, 0));

            scheduler.collectInvestOpinion();

            verify(collectionService).collect(any(LocalDate.class));
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 스레드로 전파되지 않음")
        void exceptionAbsorbed() {
            when(collectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("수집 중 예외"));

            assertThatCode(scheduler::collectInvestOpinion).doesNotThrowAnyException();
        }
    }
}
