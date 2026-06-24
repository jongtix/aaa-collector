package com.aaa.collector.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
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
@DisplayName("NewsScheduler 단위 테스트")
class NewsSchedulerTest {

    @Mock private NewsTitleCollectionService newsTitleCollectionService;
    @Mock private BatchMetrics batchMetrics;

    @InjectMocks private NewsScheduler scheduler;

    // ────────────────────────────────────────────────────────────────────
    // cron 어노테이션 검증 (REQ-BATCH3-002, REQ-BATCH3-003)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH3-002/003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0/10 9-15 * * MON-FRI', zone='Asia/Seoul'")
        void collectNews_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = NewsScheduler.class.getMethod("collectNews");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0/10 9-15 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (REQ-BATCH3-003)")
        void collectNews_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = NewsScheduler.class.getMethod("collectNews");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집 흐름
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 흐름")
    class NormalFlow {

        @Test
        @DisplayName("정상 흐름 — newsTitleCollectionService.collect() 1회 호출")
        void collectNews_normalFlow_callsService() {
            when(newsTitleCollectionService.collect())
                    .thenReturn(new NewsCollectionResult(10, 9, 1));

            scheduler.collectNews();

            verify(newsTitleCollectionService).collect();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 격리 (REQ-BATCH3-004)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 격리 — 스케줄러 스레드 보호 (REQ-BATCH3-004)")
    class ExceptionIsolation {

        @Test
        @DisplayName("서비스 예외 — 스케줄러 스레드로 전파 안 됨")
        void collectNews_serviceException_notPropagated() {
            when(newsTitleCollectionService.collect()).thenThrow(new RuntimeException("뉴스 수집 실패"));

            assertThatCode(scheduler::collectNews).doesNotThrowAnyException();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 배치 계측 (REQ-OBSV-020/021)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("배치 계측 (REQ-OBSV-020/021)")
    class BatchMetricsRecording {

        @Test
        @DisplayName("수집 완료 후 batch=domestic-news 집계 기록")
        void recordsBatchMetricsOnCompletion() {
            // Arrange — 10 시도, 9 성공, 1 스킵 → fail = 10-9-1 = 0
            when(newsTitleCollectionService.collect())
                    .thenReturn(new NewsCollectionResult(10, 9, 1));

            // Act
            scheduler.collectNews();

            // Assert
            verify(batchMetrics).recordCompletion("domestic-news", 10, 9, 0, 1);
        }

        @Test
        @DisplayName("수집 예외 시 배치 계측을 기록하지 않는다")
        void doesNotRecordOnException() {
            when(newsTitleCollectionService.collect()).thenThrow(new RuntimeException("뉴스 수집 실패"));

            scheduler.collectNews();

            verify(batchMetrics, never())
                    .recordCompletion(anyString(), anyLong(), anyLong(), anyLong(), anyLong());
        }
    }
}
