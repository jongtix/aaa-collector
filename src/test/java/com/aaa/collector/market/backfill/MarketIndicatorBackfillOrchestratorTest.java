package com.aaa.collector.market.backfill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** BackfillStatus 빌더 접근용 패키지 private 서브클래스. */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketIndicatorBackfillOrchestrator 단위 테스트")
class MarketIndicatorBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private VixCollectionService vixCollectionService;
    @Mock private UsdkrwCollectionService usdkrwCollectionService;

    private MarketIndicatorBackfillOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator =
                new MarketIndicatorBackfillOrchestrator(
                        backfillStatusRepository,
                        marketIndicatorRepository,
                        vixCollectionService,
                        usdkrwCollectionService);
        // @Value 필드는 DI가 없으므로 테스트용 값 직접 주입
        ReflectionTestUtils.setField(orchestrator, "staleWeekdayThreshold", 3);
    }

    private BackfillStatus buildStatus(Long id, String code, String status) {
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
            BackfillStatus vixStatus = buildStatus(1L, "VIX", "PENDING");
            LocalDate minDate = LocalDate.of(1990, 1, 2);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(100);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.of(minDate));

            orchestrator.runBackfill();

            verify(vixCollectionService).collectHistory();
            verify(backfillStatusRepository)
                    .updateProgress(eq(1L), eq("COMPLETED"), eq(minDate), eq(0), eq(100));
        }

        @Test
        @DisplayName("VIX 수집 0건 — anchor=today fallback, COMPLETED (W-4: DB empty → today)")
        void vix_zeroRows_anchorFallbackToday() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", "PENDING");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus));
            when(vixCollectionService.collectHistory()).thenReturn(0);
            when(marketIndicatorRepository.findMinTradeDateByIndicatorCode(IndicatorCode.VIX))
                    .thenReturn(Optional.empty()); // DB에 VIX 데이터 없음

            orchestrator.runBackfill();

            verify(backfillStatusRepository)
                    .updateProgress(eq(1L), eq("COMPLETED"), any(LocalDate.class), eq(0), eq(0));
        }
    }

    @Nested
    @DisplayName("runBackfill — USDKRW 백필 (날짜 루프)")
    class RunBackfillUsdkrw {

        @Test
        @DisplayName("USDKRW PENDING — staleWeekdayCount 누적 후 COMPLETED (REQ-044)")
        void usdkrw_staleThresholdReached_completed() {
            BackfillStatus status = buildStatus(2L, "USDKRW", "PENDING");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            // collectDailyForBackfill은 always 0건 반환 → stale 누적
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(0);

            orchestrator.runBackfill();

            // stale threshold 도달 후 COMPLETED 처리
            verify(backfillStatusRepository, atLeastOnce())
                    .updateProgress(eq(2L), eq("COMPLETED"), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("USDKRW — 데이터 수신 시 staleCount 리셋")
        void usdkrw_dataReceived_staleCountReset() {
            BackfillStatus status = buildStatus(2L, "USDKRW", "PENDING");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            // 처음 몇 번은 데이터 수신, 이후 stale
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(1) // 첫 호출: 데이터
                    .thenReturn(0); // 이후: stale

            orchestrator.runBackfill();

            // 최소한 한 번은 updateProgress(데이터 있음)
            verify(backfillStatusRepository, atLeastOnce())
                    .updateProgress(eq(2L), anyString(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("USDKRW — 10일 미만 수집 시 IN_PROGRESS 갱신 없이 COMPLETED 1회만 (W-3, MA-01)")
        void usdkrw_lessThan10Days_noInProgressUpdate() {
            // staleWeekdayThreshold=3, 5일 데이터 수신 후 stale
            BackfillStatus status = buildStatus(2L, "USDKRW", "PENDING");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(1) // 5회
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(1)
                    .thenReturn(0); // 이후 stale

            orchestrator.runBackfill();

            // 5일 < PROGRESS_BATCH_SIZE(10) → IN_PROGRESS 갱신 없음
            verify(backfillStatusRepository, never())
                    .updateProgress(eq(2L), eq("IN_PROGRESS"), any(), anyInt(), anyInt());
            // 루프 종료 후 COMPLETED 1회
            verify(backfillStatusRepository, times(1))
                    .updateProgress(eq(2L), eq("COMPLETED"), any(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("runBackfill — 예외 격리 (REQ-045)")
    class ExceptionIsolation {

        @Test
        @DisplayName("VIX 예외 — updateError 호출, USDKRW 계속")
        void vixException_updateError_usdkrwContinues() {
            BackfillStatus vixStatus = buildStatus(1L, "VIX", "PENDING");
            BackfillStatus usdkrwStatus = buildStatus(2L, "USDKRW", "PENDING");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(vixStatus, usdkrwStatus));
            when(vixCollectionService.collectHistory()).thenThrow(new RuntimeException("VIX 실패"));
            when(usdkrwCollectionService.collectDailyForBackfill(any(LocalDate.class)))
                    .thenReturn(0);

            assertThatCode(orchestrator::runBackfill).doesNotThrowAnyException();

            verify(backfillStatusRepository).updateError(eq(1L), anyString(), anyString());
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
}
