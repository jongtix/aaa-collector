package com.aaa.collector.dart.backfill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aaa.collector.observability.BatchMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DartDisclosureBackfillScheduler 단위 테스트 (SPEC-OBSV-WATERMARK-001 REQ-WM-013)")
class DartDisclosureBackfillSchedulerTest {

    @Mock private DartDisclosureBackfillOrchestrator orchestrator;
    @Mock private BatchMetrics batchMetrics;

    private DartDisclosureBackfillScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DartDisclosureBackfillScheduler(orchestrator, batchMetrics);
    }

    @Nested
    @DisplayName("run — 백필 완료 계측")
    class RunBackfill {

        @Test
        @DisplayName("백필 성공 시 dart-backfill 배치 라벨로 완료 계측한다 (dart-disclosure와 구분)")
        void run_recordsBatchCompletion() {
            scheduler.run();

            verify(orchestrator).run();
            verify(batchMetrics).recordCompletion("dart-backfill", 1, 1, 0, 0);
        }

        @Test
        @DisplayName("백필 예외 — 흡수, 계측 미호출")
        void run_absorbsExceptionWithoutRecording() {
            doThrow(new RuntimeException("백필 오류")).when(orchestrator).run();

            assertThatCode(scheduler::run).doesNotThrowAnyException();
            verifyNoInteractions(batchMetrics);
        }
    }
}
