package com.aaa.collector.market.backfill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** BackfillStatus 빌더 접근용 패키지 private 서브클래스. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MarketIndicatorBackfillOrchestrator 단위 테스트")
class MarketIndicatorBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private VixCollectionService vixCollectionService;
    @Mock private UsdkrwCollectionService usdkrwCollectionService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CoveredRangeService coveredRangeService;

    private MarketIndicatorBackfillOrchestrator orchestrator;

    // mock managed entity shared across tests
    private BackfillStatus mockManaged;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orchestrator =
                new MarketIndicatorBackfillOrchestrator(
                        backfillStatusRepository,
                        marketIndicatorRepository,
                        vixCollectionService,
                        usdkrwCollectionService,
                        transactionTemplate,
                        coveredRangeService);
        // @Value 필드는 DI가 없으므로 테스트용 값 직접 주입
        ReflectionTestUtils.setField(orchestrator, "staleWeekdayThreshold", 3);

        // TransactionTemplate.executeWithoutResult 실제 Consumer 실행
        doAnswer(
                        inv -> {
                            Consumer<TransactionStatus> action = inv.getArgument(0);
                            action.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());

        mockManaged = Mockito.mock(BackfillStatus.class);
    }

    private BackfillStatus buildStatus(Long id, String code, BackfillStatusType status) {
        BackfillStatus s =
                BackfillStatus.builder()
                        .targetType("MARKET_INDICATOR")
                        .targetCode(code)
                        .dataTable("market_indicators")
                        .status(status)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Nested
    @DisplayName("seed — 백필 시딩 (REQ-050~052)")
    class SeedingTest {

        @Test
        @DisplayName("USDKRW, VIX 2건 시딩 호출 (REQ-050)")
        void seeds_usdkrwAndVix() {
            orchestrator.seed();

            verify(backfillStatusRepository)
                    .insertIgnoreSeed("MARKET_INDICATOR", "USDKRW", "market_indicators");
            verify(backfillStatusRepository)
                    .insertIgnoreSeed("MARKET_INDICATOR", "VIX", "market_indicators");
        }
    }

    @Nested
    @DisplayName("runBackfill — VIX 백필 (전체 이력 단일 호출)")
    class RunBackfillVix {

        @Test
        @DisplayName(
                "VIX PENDING — collectHistory 후 MIN(trade_date) anchor로 COMPLETED (REQ-041~043, W-4)")
        void vix_pending_collectHistoryAndComplete() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            LocalDate minDate = LocalDate.of(1990, 1, 2);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(100);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.of(minDate));
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(vixCollectionService).collectHistory();
            verify(backfillStatusRepository).findById(1L);
            verify(mockManaged)
                    .advance(eq(BackfillStatusType.COMPLETED), eq(minDate), eq(0), eq(100));
        }

        @Test
        @DisplayName("VIX 수집 0건 — anchor=today fallback, COMPLETED (W-4: DB empty → today)")
        void vix_zeroRows_anchorFallbackToday() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty()); // DB에 VIX 데이터 없음
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            verify(mockManaged)
                    .advance(eq(BackfillStatusType.COMPLETED), any(LocalDate.class), eq(0), eq(0));
        }
    }

    @Nested
    @DisplayName("runBackfill — USDKRW 백필 (날짜 루프)")
    class RunBackfillUsdkrw {

        @Test
        @DisplayName("USDKRW PENDING — staleWeekdayCount 누적 후 COMPLETED (REQ-044)")
        void usdkrw_staleThresholdReached_completed() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(0);
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // stale threshold 도달 후 COMPLETED 처리
            verify(mockManaged, atLeastOnce())
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("USDKRW — 데이터 수신 시 staleCount 리셋")
        void usdkrw_dataReceived_staleCountReset() {
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(1) // 첫 호출: 데이터
                    .thenReturn(0); // 이후: stale
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 최소한 한 번은 advance 호출
            verify(mockManaged, atLeastOnce()).advance(any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("USDKRW — 10일 미만 수집 시 IN_PROGRESS 갱신 없이 COMPLETED 1회만 (W-3, MA-01)")
        void usdkrw_lessThan10Days_noInProgressUpdate() {
            // staleWeekdayThreshold=3, 5일 데이터 수신 후 stale
            BackfillStatus status = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(1) // 5회
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(0); // 이후 stale
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManaged));

            orchestrator.runBackfill();

            // 5일 < PROGRESS_BATCH_SIZE(10) → IN_PROGRESS advance 갱신 없음
            verify(mockManaged, never())
                    .advance(eq(BackfillStatusType.IN_PROGRESS), any(), anyInt(), anyInt());
            // 루프 종료 후 COMPLETED 1회
            verify(mockManaged, times(1))
                    .advance(eq(BackfillStatusType.COMPLETED), any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("runBackfill — 예외 격리 (REQ-045)")
    class ExceptionIsolation {

        @Test
        @DisplayName("VIX 예외 — findById 후 fail() 호출, USDKRW 계속")
        void vixException_updateError_usdkrwContinues() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            BackfillStatus usdkrwStatus = buildStatus(2L, "USDKRW", BackfillStatusType.PENDING);
            BackfillStatus mockManagedVix = Mockito.mock(BackfillStatus.class);
            BackfillStatus mockManagedUsdkrw = Mockito.mock(BackfillStatus.class);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus, usdkrwStatus));
            when(vixCollectionService.collectHistory()).thenThrow(new RuntimeException("VIX 실패"));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(0);
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManagedVix));
            when(backfillStatusRepository.findById(2L)).thenReturn(Optional.of(mockManagedUsdkrw));

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(mockManagedVix).fail(any(), anyString());
        }

        @Test
        @DisplayName("처리 대상 없음 — 아무것도 하지 않음 (REQ-047)")
        void noTargets_noop() {
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(vixCollectionService, never()).collectHistory();
            verify(usdkrwCollectionService, never()).collectHistory();
        }
    }

    @Nested
    @DisplayName("runBackfill — USDKRW 커버-추적 배선 (SPEC-COLLECTOR-BACKFILL-011 AC-12, AC-15)")
    class UsdkrwCoveredGapWalk {

        @Test
        @DisplayName("①②③ 기존 USDKRW 행 재사용 — walkGapForward가 그 행으로 호출됨(신규 행 생성 없음, REQ-CVR-070)")
        void reusesExistingUsdkrwRow_walksGapForward() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of()); // backward walk 대상 없음(이미 COMPLETED)
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));

            orchestrator.runBackfill();

            // 기존 행을 그대로 조회·재사용했을 뿐, 신규 backfill_status 행을 생성하는 호출은 없다(insertIgnoreSeed는
            // seed()에서만 발생 — runBackfill 경로에서 호출되지 않음이 곧 "신규 행 미생성"의 증거).
            verify(backfillStatusRepository, never()).insertIgnoreSeed(any(), any(), any());
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw),
                            any(UsdkrwCoveredGapFiller.class),
                            any(LocalDate.class));
        }

        @Test
        @DisplayName("④ 갭 채우기는 collectDailyForBackfill 재사용 — UsdkrwCoveredGapFiller가 이를 감싸 전달됨")
        void gapFillerWrapsExistingBackfillPath() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));
            LocalDate cursor = LocalDate.of(2026, 7, 1);
            when(usdkrwCollectionService.collectDailyForBackfillWithRaw(cursor))
                    .thenReturn(new UsdkrwCollectionService.SaveOutcome(1, 1));

            orchestrator.runBackfill();

            ArgumentCaptor<UsdkrwCoveredGapFiller> fillerCaptor =
                    ArgumentCaptor.forClass(UsdkrwCoveredGapFiller.class);
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw), fillerCaptor.capture(), any(LocalDate.class));
            fillerCaptor.getValue().persistStep(cursor);
            // filler가 결국 usdkrwCollectionService의 기존 백필 경로를 호출함을 확인 — 신규 fetch 메서드가 아니다.
            verify(usdkrwCollectionService).collectDailyForBackfillWithRaw(cursor);
        }

        @Test
        @DisplayName("⑤ VIX는 여전히 커버-추적에서 제외 — walkGapForward는 USDKRW 행에만 호출된다")
        void vixExcluded_walkGapForwardOnlyForUsdkrw() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", BackfillStatusType.PENDING);
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty());
            when(backfillStatusRepository.findById(1L)).thenReturn(Optional.of(mockManaged));
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));

            orchestrator.runBackfill();

            verify(coveredRangeService, times(1))
                    .walkGapForward(any(), any(), any(LocalDate.class));
            verify(coveredRangeService)
                    .walkGapForward(
                            eq(existingUsdkrw),
                            any(UsdkrwCoveredGapFiller.class),
                            any(LocalDate.class));
        }

        @Test
        @DisplayName("USDKRW 행 미시딩 상태(seed 이전) — walkGapForward 미호출, 예외 없음")
        void usdkrwRowNotSeeded_walkGapForwardSkipped() {
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.empty());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(coveredRangeService, never()).walkGapForward(any(), any(), any());
        }

        @Test
        @DisplayName("갭 walk 예외 — runBackfill 전체를 중단시키지 않는다(REQ-045 예외 격리 정신 재사용)")
        void gapWalkException_doesNotPropagate() {
            BackfillStatus existingUsdkrw = buildStatus(2L, "USDKRW", BackfillStatusType.COMPLETED);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "MARKET_INDICATOR", "USDKRW", "market_indicators"))
                    .thenReturn(Optional.of(existingUsdkrw));
            Mockito.doThrow(new RuntimeException("gap walk 실패"))
                    .when(coveredRangeService)
                    .walkGapForward(any(), any(), any());

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();
        }
    }
}
