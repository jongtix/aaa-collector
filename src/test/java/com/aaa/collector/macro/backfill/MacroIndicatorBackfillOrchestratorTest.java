package com.aaa.collector.macro.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.ecos.EcosSeriesConfig;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.macro.fred.FredSeriesConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T6 RED — MacroIndicatorBackfillOrchestrator 단위 테스트 (SPEC-COLLECTOR-MACRO-EXT-001).
 *
 * <p>13개 시딩, PENDING/IN_PROGRESS 조회, collectAll 호출, updateProgress(COMPLETED), updateError 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MacroIndicatorBackfillOrchestrator — 단위 테스트")
class MacroIndicatorBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private EcosCollectionService ecosCollectionService;
    @Mock private FredCollectionService fredCollectionService;

    @InjectMocks private MacroIndicatorBackfillOrchestrator orchestrator;

    // ────────────────────────────────────────────────────────────────────
    // 시딩
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("시딩 — 13개 MACRO_INDICATOR 항목")
    class Seeding {

        @Test
        @DisplayName("run() 호출 시 insertIgnoreSeed 13회 호출 (8 ECOS + 5 FRED)")
        void run_seeds13Entries() {
            // Arrange
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of());

            // Act
            orchestrator.run();

            // Assert — 8 ECOS + 5 FRED = 13회
            verify(backfillStatusRepository, times(13))
                    .insertIgnoreSeed(eq("MACRO_INDICATOR"), anyString(), eq("macro_indicators"));
        }

        @Test
        @DisplayName("ECOS indicator_code 8개 모두 시딩")
        void run_seedsAllEcosCodes() {
            // Arrange
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of());

            // Act
            orchestrator.run();

            // Assert
            ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
            verify(backfillStatusRepository, times(13))
                    .insertIgnoreSeed(
                            eq("MACRO_INDICATOR"), codeCaptor.capture(), eq("macro_indicators"));

            List<String> codes = codeCaptor.getAllValues();
            EcosSeriesConfig.ALL.forEach(s -> assertThat(codes).contains(s.indicatorCode()));
            FredSeriesConfig.ALL.forEach(s -> assertThat(codes).contains(s.indicatorCode()));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // PENDING/IN_PROGRESS 처리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PENDING/IN_PROGRESS 항목 처리")
    class Processing {

        @Test
        @DisplayName("PENDING ECOS 항목 — ecosCollectionService.collectAll() 호출 후 COMPLETED 갱신")
        void pendingEcosEntry_callsCollectAllAndUpdatesCompleted() {
            // Arrange
            BackfillStatus status = mockStatus(1L, "ECOS_BASE_RATE");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(100, 95, 5));

            // Act
            orchestrator.run();

            // Assert
            verify(ecosCollectionService).collectAll();
            verify(backfillStatusRepository)
                    .updateProgress(eq(1L), eq("COMPLETED"), any(LocalDate.class), eq(0), any());
        }

        @Test
        @DisplayName("PENDING FRED 항목 — fredCollectionService.collectAll() 호출 후 COMPLETED 갱신")
        void pendingFredEntry_callsCollectAllAndUpdatesCompleted() {
            // Arrange
            BackfillStatus status = mockStatus(2L, "FRED_DFF");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(fredCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(50, 48, 2));

            // Act
            orchestrator.run();

            // Assert
            verify(fredCollectionService).collectAll();
            verify(backfillStatusRepository)
                    .updateProgress(eq(2L), eq("COMPLETED"), any(LocalDate.class), eq(0), any());
        }

        @Test
        @DisplayName("STOCK/MARKET_INDICATOR target_type 항목 — 처리 안 함 (MACRO_INDICATOR 필터)")
        void nonMacroTargetType_notProcessed() {
            // Arrange — findByStatusInAndTargetTypeOrderById 는 MACRO_INDICATOR 필터링됨
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of()); // 쿼리 자체가 MACRO_INDICATOR만 반환

            // Act
            orchestrator.run();

            // Assert — collectAll 호출 없음
            verify(ecosCollectionService, never()).collectAll();
            verify(fredCollectionService, never()).collectAll();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 처리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 처리 — updateError 호출")
    class ErrorHandling {

        @Test
        @DisplayName("collectAll() 예외 — updateError 호출")
        void collectAllException_callsUpdateError() {
            // Arrange
            BackfillStatus status = mockStatus(3L, "ECOS_BASE_RATE");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll()).thenThrow(new RuntimeException("API error"));

            // Act
            orchestrator.run();

            // Assert
            verify(backfillStatusRepository).updateError(eq(3L), eq("FAILED"), anyString());
            verify(backfillStatusRepository, never())
                    .updateProgress(any(), anyString(), any(), any(Integer.class), any());
        }
    }

    private BackfillStatus mockStatus(Long id, String targetCode) {
        BackfillStatus status = org.mockito.Mockito.mock(BackfillStatus.class);
        when(status.getId()).thenReturn(id);
        when(status.getTargetCode()).thenReturn(targetCode);
        return status;
    }
}
