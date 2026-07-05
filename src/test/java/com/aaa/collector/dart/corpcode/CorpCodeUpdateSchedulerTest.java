package com.aaa.collector.dart.corpcode;

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
@DisplayName("CorpCodeUpdateScheduler 단위 테스트 (SPEC-OBSV-WATERMARK-001 REQ-WM-013)")
class CorpCodeUpdateSchedulerTest {

    @Mock private CorpCodeUpdateService corpCodeUpdateService;
    @Mock private BatchMetrics batchMetrics;

    private CorpCodeUpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CorpCodeUpdateScheduler(corpCodeUpdateService, batchMetrics);
    }

    @Nested
    @DisplayName("update — 갱신 완료 계측")
    class Update {

        @Test
        @DisplayName("갱신 성공 시 corp-code 배치 라벨로 완료 계측한다")
        void update_recordsBatchCompletion() {
            scheduler.update();

            verify(corpCodeUpdateService).update();
            verify(batchMetrics).recordCompletion("corp-code", 1, 1, 0, 0);
        }

        @Test
        @DisplayName("갱신 예외 — 흡수, 계측 미호출")
        void update_absorbsExceptionWithoutRecording() {
            doThrow(new RuntimeException("갱신 오류")).when(corpCodeUpdateService).update();

            assertThatCode(scheduler::update).doesNotThrowAnyException();
            verifyNoInteractions(batchMetrics);
        }
    }
}
