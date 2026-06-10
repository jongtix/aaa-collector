package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.LocalDate;
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
@DisplayName("DomesticDailyOhlcvScheduler 단위 테스트")
class DomesticDailyOhlcvSchedulerTest {

    @Mock private DomesticDailyOhlcvCollectionService collectionService;
    @Mock private DailyCompletePublisher publisher;

    @InjectMocks private DomesticDailyOhlcvScheduler scheduler;

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH-050, REQ-BATCH-051)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 16 * * MON-FRI', zone='Asia/Seoul' (AC-6 S6-1, S6-2)")
        void collectDaily_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = DomesticDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 16 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (REQ-BATCH-051, AC-6 S6-2)")
        void collectDaily_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = DomesticDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectDaily — 수집 흐름")
    class CollectDailyFlow {

        @Test
        @DisplayName("정상 흐름 — collect + publish 호출")
        void collectDaily_normalFlow_collectsAndPublishes() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            // Act
            scheduler.collectDaily();

            // Assert
            verify(collectionService).collect(any(LocalDate.class));
            verify(publisher).publish(result);
        }

        @Test
        @DisplayName("collect에서 예외 — 예외 흡수, publish 미호출")
        void collectDaily_exceptionInCollect_absorbedNoPropagation() {
            // Arrange
            Mockito.when(collectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("수집 중 예외"));

            // Act — 예외가 전파되지 않아야 함
            assertThatCode(scheduler::collectDaily).doesNotThrowAnyException();
        }
    }
}
