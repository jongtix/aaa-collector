package com.aaa.collector.macro.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.ecos.EcosSeriesConfig;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.macro.fred.FredSeriesConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T6 RED — MacroIndicatorBackfillOrchestrator 단위 테스트 (SPEC-COLLECTOR-MACRO-EXT-001).
 *
 * <p>13개 시딩, PENDING/IN_PROGRESS 조회, collectAll 호출, updateProgress(COMPLETED), updateError 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MacroIndicatorBackfillOrchestrator — 단위 테스트")
class MacroIndicatorBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private EcosCollectionService ecosCollectionService;
    @Mock private FredCollectionService fredCollectionService;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private MacroIndicatorBackfillOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpTransactionTemplate() {
        doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> action = inv.getArgument(0);
                            action.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

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
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(100, 95, 5));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode("ECOS_BASE_RATE"))
                    .thenReturn(Optional.of(LocalDate.of(2020, 1, 2)));
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert
            verify(ecosCollectionService).collectAll();
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged).advance(eq("COMPLETED"), any(LocalDate.class), eq(0), any());
        }

        @Test
        @DisplayName("PENDING FRED 항목 — fredCollectionService.collectAll() 호출 후 COMPLETED 갱신")
        void pendingFredEntry_callsCollectAllAndUpdatesCompleted() {
            // Arrange
            BackfillStatus status = mockStatus(2L, "FRED_DFF");
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(fredCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(50, 48, 2));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode("FRED_DFF"))
                    .thenReturn(Optional.of(LocalDate.of(2019, 6, 1)));
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert
            verify(fredCollectionService).collectAll();
            verify(backfillStatusRepository).findById(2L);
            verify(mockManaged).advance(eq("COMPLETED"), any(LocalDate.class), eq(0), any());
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
    // W-1: minDate — 실제 최소 거래일로 updateProgress 호출 (REQ-MACRO-EXT-072)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("W-1: minDate — last_collected_date 갱신 (REQ-MACRO-EXT-072)")
    class MinDateUpdate {

        @Test
        @DisplayName("수집 후 findMinTradeDateByIndicatorCode 반환값으로 advance() 호출")
        void processEntry_usesActualMinDateFromRepository() {
            // Arrange
            LocalDate expectedMin = LocalDate.of(2015, 3, 20);
            BackfillStatus status = mockStatus(10L, "ECOS_BASE_RATE");
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(200, 200, 0));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode("ECOS_BASE_RATE"))
                    .thenReturn(Optional.of(expectedMin));
            when(backfillStatusRepository.findById(10L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert — minDate가 오늘이 아닌 실제 최소 거래일
            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(mockManaged).advance(eq("COMPLETED"), dateCaptor.capture(), eq(0), eq(200));
            assertThat(dateCaptor.getValue()).isEqualTo(expectedMin);
        }

        @Test
        @DisplayName("findMinTradeDateByIndicatorCode가 empty 반환 시 LocalDate.now() fallback")
        void processEntry_fallsBackToTodayWhenRepositoryReturnsEmpty() {
            // Arrange
            BackfillStatus status = mockStatus(11L, "ECOS_BASE_RATE");
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll()).thenReturn(new MacroCollectionResult(0, 0, 0));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode("ECOS_BASE_RATE"))
                    .thenReturn(Optional.empty());
            when(backfillStatusRepository.findById(11L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert — empty 시 오늘 날짜로 fallback
            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(mockManaged).advance(eq("COMPLETED"), dateCaptor.capture(), eq(0), eq(0));
            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // W-2: FRED 경로 파라미터 테스트 (AC-4.2)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("W-2: AC-4.2 FRED 지표 코드 — fredCollectionService 분기 검증")
    class FredRoutingParameterized {

        @ParameterizedTest(name = "FRED 코드={0} → fredCollectionService.collectAll() 호출")
        @ValueSource(
                strings = {
                    "FRED_DFF",
                    "FRED_DGS10",
                    "FRED_CPIAUCSL",
                    "FRED_A191RL1Q225SBEA",
                    "FRED_UNRATE"
                })
        @DisplayName("FRED indicator_code 5개 전체 — fredCollectionService.collectAll() 라우팅")
        void fredIndicatorCode_routesToFredCollectionService(String indicatorCode) {
            // Arrange
            BackfillStatus status = mockStatus(20L, indicatorCode);
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(fredCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(100, 100, 0));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode(indicatorCode))
                    .thenReturn(Optional.of(LocalDate.of(2000, 1, 3)));
            when(backfillStatusRepository.findById(20L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert
            verify(fredCollectionService).collectAll();
            verify(ecosCollectionService, never()).collectAll();
            verify(mockManaged).advance(eq("COMPLETED"), any(LocalDate.class), eq(0), eq(100));
        }

        @ParameterizedTest(name = "ECOS 코드={0} → ecosCollectionService.collectAll() 호출")
        @ValueSource(
                strings = {
                    "ECOS_BASE_RATE",
                    "ECOS_GOV_BOND_3Y",
                    "ECOS_GOV_BOND_5Y",
                    "ECOS_GOV_BOND_10Y",
                    "ECOS_CORP_BOND",
                    "ECOS_CPI",
                    "ECOS_GDP_QOQ",
                    "ECOS_CURRENT_ACCOUNT"
                })
        @DisplayName("ECOS indicator_code 8개 전체 — ecosCollectionService.collectAll() 라우팅")
        void ecosIndicatorCode_routesToEcosCollectionService(String indicatorCode) {
            // Arrange
            BackfillStatus status = mockStatus(21L, indicatorCode);
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll())
                    .thenReturn(new MacroCollectionResult(80, 80, 0));
            when(macroIndicatorRepository.findMinTradeDateByIndicatorCode(indicatorCode))
                    .thenReturn(Optional.of(LocalDate.of(2005, 1, 4)));
            when(backfillStatusRepository.findById(21L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert
            verify(ecosCollectionService).collectAll();
            verify(fredCollectionService, never()).collectAll();
            verify(mockManaged).advance(eq("COMPLETED"), any(LocalDate.class), eq(0), eq(80));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 처리
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 처리 — updateError 호출")
    class ErrorHandling {

        @Test
        @DisplayName("collectAll() 예외 — findById 후 fail() 호출")
        void collectAllException_callsUpdateError() {
            // Arrange
            BackfillStatus status = mockStatus(3L, "ECOS_BASE_RATE");
            BackfillStatus mockManaged = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                            any(), eq("MACRO_INDICATOR")))
                    .thenReturn(List.of(status));
            when(ecosCollectionService.collectAll()).thenThrow(new RuntimeException("API error"));
            when(backfillStatusRepository.findById(3L)).thenReturn(Optional.of(mockManaged));

            // Act
            orchestrator.run();

            // Assert
            verify(backfillStatusRepository).findById(3L);
            verify(mockManaged).fail(eq("FAILED"), anyString());
            verify(mockManaged, never()).advance(any(), any(), any(Integer.class), any());
        }
    }

    private BackfillStatus mockStatus(Long id, String targetCode) {
        BackfillStatus status = org.mockito.Mockito.mock(BackfillStatus.class);
        when(status.getId()).thenReturn(id);
        when(status.getTargetCode()).thenReturn(targetCode);
        return status;
    }
}
