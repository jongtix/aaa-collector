package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.observability.BatchMetrics;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * MacroExternalScheduler 단위 테스트 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-041).
 *
 * <p>cron 어노테이션, ECOS→FRED 순서, 예외 격리, 배치 계측 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MacroExternalScheduler — 단위 테스트")
class MacroExternalSchedulerTest {

    @Mock private EcosCollectionService ecosCollectionService;
    @Mock private FredCollectionService fredCollectionService;
    @Mock private BatchMetrics batchMetrics;

    @InjectMocks private MacroExternalScheduler scheduler;

    // ────────────────────────────────────────────────────────────────────
    // cron 어노테이션
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("@Scheduled 어노테이션")
    class ScheduledAnnotation {

        @Test
        @DisplayName(
                "collectExternal 메서드에 @Scheduled(cron='0 0 19 * * MON-FRI', zone='Asia/Seoul') 어노테이션 존재")
        void collectExternal_hasCorrectScheduledAnnotation() throws Exception {
            Method method = MacroExternalScheduler.class.getMethod("collectExternal");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 19 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 어노테이션 없음 (Virtual Threads 버그 방지)")
        void collectExternal_noFixedDelayOrRate() throws Exception {
            Method method = MacroExternalScheduler.class.getMethod("collectExternal");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // ECOS → FRED 순서
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("수집 순서 — ECOS → FRED")
    class CollectionOrder {

        @Test
        @DisplayName("collectExternal() — ECOS.collect() 후 FRED.collect() 순차 호출")
        void collectExternal_ecosBeforeFred() {
            // Arrange
            when(ecosCollectionService.collect()).thenReturn(new MacroCollectionResult(0, 0, 0));
            when(fredCollectionService.collect()).thenReturn(new MacroCollectionResult(0, 0, 0));

            // Act
            scheduler.collectExternal();

            // Assert — ECOS 먼저, FRED 나중
            InOrder inOrder = inOrder(ecosCollectionService, fredCollectionService);
            inOrder.verify(ecosCollectionService).collect();
            inOrder.verify(fredCollectionService).collect();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 격리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 격리 — ECOS 실패 시 FRED 계속")
    class ExceptionIsolation {

        @Test
        @DisplayName("ECOS.collect() 예외 — 예외 전파 없이 FRED.collect() 계속 호출")
        void ecosException_fredStillCalled() {
            // Arrange
            doThrow(new RuntimeException("ECOS network error"))
                    .when(ecosCollectionService)
                    .collect();
            when(fredCollectionService.collect()).thenReturn(new MacroCollectionResult(0, 0, 0));

            // Act — 예외가 collectExternal()로 전파되지 않아야 함
            assertThatCode(scheduler::collectExternal).doesNotThrowAnyException();

            // Assert — FRED는 계속 호출됨
            verify(fredCollectionService).collect();
        }

        @Test
        @DisplayName("FRED.collect() 예외 — 예외 전파 없이 종료")
        void fredException_noExceptionPropagated() {
            // Arrange
            when(ecosCollectionService.collect()).thenReturn(new MacroCollectionResult(0, 0, 0));
            doThrow(new RuntimeException("FRED network error"))
                    .when(fredCollectionService)
                    .collect();

            // Act & Assert — 예외가 전파되지 않아야 함
            assertThatCode(scheduler::collectExternal).doesNotThrowAnyException();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 배치 계측 (REQ-OBSV-020/021)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("배치 계측 — ECOS+FRED 합산 (REQ-OBSV-020/021)")
    class BatchMetricsRecording {

        @Test
        @DisplayName("ECOS+FRED 정상 완료 — 합산 결과를 batch=macro-external로 기록")
        void recordsBatchMetricsOnCompletion() {
            // Arrange — ECOS: 5 시도 5 성공, FRED: 3 시도 3 성공 → 합계 attempted=8, success=8, fail=0,
            // skip=0
            when(ecosCollectionService.collect()).thenReturn(new MacroCollectionResult(5, 5, 0));
            when(fredCollectionService.collect()).thenReturn(new MacroCollectionResult(3, 3, 0));

            // Act
            scheduler.collectExternal();

            // Assert
            verify(batchMetrics).recordCompletion("macro-external", 8, 8, 0, 0);
        }

        @Test
        @DisplayName("ECOS 예외 — ECOS fail=1 계상, FRED 정상 합산, 1회 recordCompletion 호출")
        void ecosException_countsAsOneFail() {
            // Arrange — ECOS 예외(succeeded=0, fail=1), FRED: 3 시도 3 성공
            doThrow(new RuntimeException("ECOS 장애")).when(ecosCollectionService).collect();
            when(fredCollectionService.collect()).thenReturn(new MacroCollectionResult(3, 3, 0));

            // Act
            scheduler.collectExternal();

            // Assert — attempted=4(1+3), success=3, fail=1, skip=0
            verify(batchMetrics).recordCompletion("macro-external", 4, 3, 1, 0);
        }

        @Test
        @DisplayName("FRED 예외 — FRED fail=1 계상, ECOS 정상 합산, 1회 recordCompletion 호출")
        void fredException_countsAsOneFail() {
            // Arrange — ECOS: 5 시도 5 성공, FRED 예외
            when(ecosCollectionService.collect()).thenReturn(new MacroCollectionResult(5, 5, 0));
            doThrow(new RuntimeException("FRED 장애")).when(fredCollectionService).collect();

            // Act
            scheduler.collectExternal();

            // Assert — attempted=6(5+1), success=5, fail=1, skip=0
            verify(batchMetrics).recordCompletion("macro-external", 6, 5, 1, 0);
        }
    }
}
