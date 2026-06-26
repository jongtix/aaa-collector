package com.aaa.collector.stock.backfill;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusSeeder;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackfillOrchestrator 단위 테스트")
class BackfillOrchestratorTest {

    @Mock private BackfillStatusSeeder seeder;
    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private BackfillWindowExecutor windowExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private StockRepository stockRepository;
    @Mock private BackfillProperties properties;
    @Mock private BackfillMetrics backfillMetrics;

    @InjectMocks private BackfillOrchestrator orchestrator;

    private LeaseSession session;

    @BeforeEach
    void setUp() {
        session = mock(LeaseSession.class);
        when(keyLeaseRegistry.openSession()).thenReturn(session);

        // 기본 테이블별 완성 캡·윈도우 상한 설정 (lenient — 일부 테스트는 도달하지 않음)
        when(properties.getPerTableCompletionCap()).thenReturn(10);
        when(properties.getMaxWindowsPerTarget()).thenReturn(120);
    }

    private Stock buildDomesticStock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private BackfillStatus buildPendingStatus(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status("PENDING")
                .staleCount(0)
                .attemptCount(0)
                .build();
    }

    private BackfillStatus buildInProgressStatus(
            String symbol, String dataTable, LocalDate lastCollectedDate) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status("IN_PROGRESS")
                .lastCollectedDate(lastCollectedDate)
                .staleCount(0)
                .attemptCount(0)
                .build();
    }

    private BackfillStatus buildCompletedStatus(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status("COMPLETED")
                .staleCount(0)
                .attemptCount(0)
                .build();
    }

    // -----------------------------------------------------------------------
    // AC-1: inner 완성 루프 (REQ-BACKFILL-050/-051/-057)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-1: inner 완성 루프")
    class InnerCompletionLoop {

        @Test
        @DisplayName(
                "AC-1.1 — daily_ohlcv 단일 회차에서 3 윈도우 후 COMPLETED, executeWindow 3회 (REQ-BACKFILL-050/-057)")
        void ac1_1_innerLoop_repeatsUntilCompleted_groupA() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            BackfillStatus initial = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus refreshed1 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 1, 1));
            BackfillStatus refreshed2 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2023, 7, 1));
            BackfillStatus refreshed3 = buildCompletedStatus("005930", "daily_ohlcv");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(refreshed1))
                    .thenReturn(Optional.of(refreshed2))
                    .thenReturn(Optional.of(refreshed3));

            // Act
            orchestrator.run();

            // Assert — 같은 회차에서 3회 호출됨
            verify(windowExecutor, times(3)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName(
                "AC-1.2 — GROUP_B(investor_trend) 무전진 누적으로 COMPLETED, 첫 무전진에서 break 없음 (REQ-BACKFILL-051)")
        void ac1_2_groupB_staleAccumulation_notBrokenByFirstStaleness()
                throws InterruptedException {
            // Arrange — investor_trend(GROUP_B), staleWindowThreshold=3 → 3회 무전진 후 COMPLETED
            Stock stock = buildDomesticStock("005930");
            LocalDate anchor = LocalDate.of(2024, 6, 1);
            BackfillStatus initial = buildPendingStatus("005930", "investor_trend");
            BackfillStatus stale1 = buildInProgressStatus("005930", "investor_trend", anchor);
            BackfillStatus stale2 = buildInProgressStatus("005930", "investor_trend", anchor);
            BackfillStatus completed = buildCompletedStatus("005930", "investor_trend");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(stale1))
                    .thenReturn(Optional.of(stale2))
                    .thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 3회 호출됨(첫 무전진에서 중단되지 않음)
            verify(windowExecutor, times(3)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName("AC-1.3 — 첫 윈도우 후 COMPLETED, executeWindow 1회만 (REQ-BACKFILL-057)")
        void ac1_3_completedAfterFirstWindow_singleCall() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            BackfillStatus initial = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 1회만 호출됨
            verify(windowExecutor, times(1)).executeWindow(any(), eq(stock), eq(session));
        }
    }

    // -----------------------------------------------------------------------
    // AC-2: 무한 루프 방지 안전장치 (REQ-BACKFILL-053a/-053b)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-2: 무한 루프 방지 안전장치")
    class InfiniteLoopGuard {

        @Test
        @DisplayName(
                "AC-2.1 — 공통 하드 캡(maxWindowsPerTarget=3) 도달 시 정확히 3회 호출 후 중단 (REQ-BACKFILL-053a)")
        void ac2_1_commonHardCap_stopsAtMaxWindows() throws InterruptedException {
            // Arrange — maxWindowsPerTarget=3, 항상 IN_PROGRESS 반환(종료 판정 없음)
            when(properties.getMaxWindowsPerTarget()).thenReturn(3);

            Stock stock = buildDomesticStock("005930");
            BackfillStatus initial = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus inProg1 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 3, 1));
            BackfillStatus inProg2 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 1, 1));
            BackfillStatus inProg3 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2023, 11, 1));

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(inProg1))
                    .thenReturn(Optional.of(inProg2))
                    .thenReturn(Optional.of(inProg3));

            // Act
            orchestrator.run();

            // Assert — 정확히 3회(하드 캡 도달)
            verify(windowExecutor, times(3)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName("AC-2.2 — GROUP_A(daily_ohlcv) anchor 무전진 시 즉시 중단 (REQ-BACKFILL-053b)")
        void ac2_2_groupA_anchorNoProgress_immediateBreak() throws InterruptedException {
            // Arrange — daily_ohlcv(GROUP_A), 첫 윈도우 후 lastCollectedDate 불변(무전진)
            Stock stock = buildDomesticStock("005930");
            LocalDate anchorDate = LocalDate.of(2024, 1, 1);
            BackfillStatus initial = buildInProgressStatus("005930", "daily_ohlcv", anchorDate);
            BackfillStatus stalled = buildInProgressStatus("005930", "daily_ohlcv", anchorDate);
            BackfillStatus stalled2 = buildInProgressStatus("005930", "daily_ohlcv", anchorDate);

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(stalled))
                    .thenReturn(Optional.of(stalled2));

            // Act
            orchestrator.run();

            // Assert — 1회만 호출(첫 무전진에서 즉시 중단)
            verify(windowExecutor, times(1)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName("AC-2.3 — GROUP_A 정상 전진·첫 윈도우 면제는 중단 없이 계속 (REQ-BACKFILL-053b 음성)")
        void ac2_3_groupA_normalProgress_firstWindowExemption_noBreak()
                throws InterruptedException {
            // Arrange — daily_ohlcv(GROUP_A), lastCollectedDate가 매번 과거로 전진
            Stock stock = buildDomesticStock("005930");
            BackfillStatus initial = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus inProg1 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 6, 1));
            BackfillStatus inProg2 =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 3, 1));
            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(inProg1))
                    .thenReturn(Optional.of(inProg2))
                    .thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 3회 모두 호출됨(무전진 break 없음)
            verify(windowExecutor, times(3)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName(
                "AC-2.4 — GROUP_B(credit_balance) 무전진에서 break 미적용, executeWindow 재호출 (D1 핵심 회귀 가드)")
        void ac2_4_groupB_noProgressDoesNotBreak_executesWindowAgain() throws InterruptedException {
            // Arrange — credit_balance(GROUP_B), 첫 윈도우 후 lastCollectedDate 불변(무전진=정상)
            Stock stock = buildDomesticStock("005930");
            LocalDate anchor = LocalDate.of(2024, 6, 1);
            BackfillStatus initial =
                    buildInProgressStatus("005930", "credit_balance", LocalDate.of(2024, 7, 1));
            BackfillStatus stale1 = buildInProgressStatus("005930", "credit_balance", anchor);
            BackfillStatus completed = buildCompletedStatus("005930", "credit_balance");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(initial));
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(stale1))
                    .thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 2회 호출됨(첫 무전진에서 중단되지 않고 executeWindow 재호출)
            verify(windowExecutor, times(2)).executeWindow(any(), eq(stock), eq(session));
        }
    }

    // -----------------------------------------------------------------------
    // AC-3: 테이블 병렬 처리 (REQ-BACKFILL-061/-062/-063)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-3: 테이블 병렬 처리")
    class TableParallelism {

        @Test
        @DisplayName("AC-3.1 — 서로 다른 테이블의 pending status가 모두 처리됨 (REQ-BACKFILL-063, 기아 해소 핵심)")
        void ac3_1_allTablesGetOwnStream() throws InterruptedException {
            // Arrange: 4개 테이블 각각 1개 status — 단일 종목 "005930"
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus s1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus s2 = buildPendingStatus("005930", "investor_trend");
            BackfillStatus s3 = buildPendingStatus("005930", "credit_balance");
            BackfillStatus s4 = buildPendingStatus("005930", "short_sale_domestic");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(s1, s2, s3, s4));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert: 4개 테이블 각각 1회씩 = 총 4회 처리됨 (병렬, 비결정적 순서)
            verify(windowExecutor, times(4)).executeWindow(any(), eq(stock), eq(session));
        }

        @Test
        @DisplayName(
                "AC-3.2 — daily_ohlcv 기아 회귀 가드: 저ID 테이블이 많아도 daily_ohlcv 독립 스트림으로 처리됨"
                        + " (REQ-BACKFILL-061)")
        void ac3_2_dailyOhlcv_notStarvedByHigherVolumeTable() throws InterruptedException {
            // Arrange: investor_trend 2개 status + daily_ohlcv 1개 status
            // per-table-cap=1 → investor_trend cap 도달해도 daily_ohlcv는 별도 스트림에서 처리됨
            when(properties.getPerTableCompletionCap()).thenReturn(1);

            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus it1 = buildPendingStatus("005930", "investor_trend");
            BackfillStatus it2 = buildPendingStatus("005930", "investor_trend");
            BackfillStatus ohlcv = buildPendingStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(it1, it2, ohlcv));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert: investor_trend 1개 + daily_ohlcv 1개 = 총 2회 처리
            // (기존 전역 캡 구현이었다면 investor_trend cap=1 소진 후 daily_ohlcv가 처리되지 않았음)
            verify(windowExecutor, times(2)).executeWindow(any(), eq(stock), eq(session));
        }
    }

    // -----------------------------------------------------------------------
    // AC-5: 테이블별 캡 독립 적용 (REQ-BACKFILL-064)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-5: 테이블별 캡")
    class PerTableCompletionCap {

        @Test
        @DisplayName("AC-5.1 — 테이블별 캡=2, 같은 테이블 status 3개 중 2개만 처리 (REQ-BACKFILL-064)")
        void ac5_1_perTableCap_limitsStatusSlotsWithinOneTable() throws InterruptedException {
            // Arrange — cap=2, 3개 stock 모두 daily_ohlcv → 1개 VT 스트림, cap=2 → 2개 처리
            when(properties.getPerTableCompletionCap()).thenReturn(2);

            Stock stock1 = buildDomesticStock("005930");
            Stock stock2 = buildDomesticStock("000660");
            Stock stock3 = buildDomesticStock("003550");
            when(stockRepository.findAllActiveTradable())
                    .thenReturn(List.of(stock1, stock2, stock3));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            // 모두 daily_ohlcv 테이블 → 1개 VT 스트림
            BackfillStatus s1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus s2 = buildPendingStatus("000660", "daily_ohlcv");
            BackfillStatus s3 = buildPendingStatus("003550", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(s1, s2, s3));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 2개 status만 처리(3번째는 per-table cap 도달로 스킵)
            verify(windowExecutor, times(2)).executeWindow(any(), any(), eq(session));
        }

        @Test
        @DisplayName(
                "AC-5.2 — 다윈도우(5 window) status도 슬롯 1개만 소비, 같은 테이블 두 번째 status 미처리 (REQ-BACKFILL-064)")
        void ac5_2_multiWindowStatus_consumesSingleSlotPerTable() throws InterruptedException {
            // Arrange — per-table-cap=1, daily_ohlcv 2개 status (005930: 5윈도우 후 완료, 000660: 미처리)
            when(properties.getPerTableCompletionCap()).thenReturn(1);

            Stock stock1 = buildDomesticStock("005930");
            Stock stock2 = buildDomesticStock("000660");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock1, stock2));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            // 모두 daily_ohlcv 테이블 → 1개 스트림, cap=1 → 첫 번째 status(5윈도우)만 처리
            BackfillStatus s1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus s2 = buildPendingStatus("000660", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(s1, s2));

            // s1: 4회 IN_PROGRESS 후 COMPLETED
            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(
                            Optional.of(
                                    buildInProgressStatus(
                                            "005930", "daily_ohlcv", LocalDate.of(2024, 4, 1))))
                    .thenReturn(
                            Optional.of(
                                    buildInProgressStatus(
                                            "005930", "daily_ohlcv", LocalDate.of(2024, 2, 1))))
                    .thenReturn(
                            Optional.of(
                                    buildInProgressStatus(
                                            "005930", "daily_ohlcv", LocalDate.of(2024, 1, 1))))
                    .thenReturn(
                            Optional.of(
                                    buildInProgressStatus(
                                            "005930", "daily_ohlcv", LocalDate.of(2023, 11, 1))))
                    .thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — s1만 처리(5회), s2는 슬롯 소진으로 미처리
            verify(windowExecutor, times(5)).executeWindow(any(), any(), eq(session));
        }

        @Test
        @DisplayName("AC-5.3 — 비활성 종목 status는 inner 루프 미개시·슬롯 미소비 (BACKFILL-001 AC-7.4 유지)")
        void ac5_3_inactiveStock_skipped_noSlotConsumed() throws InterruptedException {
            // Arrange — 활성종목: 005930만, 000660은 비활성
            when(properties.getPerTableCompletionCap()).thenReturn(1);

            Stock activeStock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(activeStock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            // 모두 daily_ohlcv — 1개 스트림. 비활성(000660)은 스킵(슬롯 미소비) → 활성(005930) 처리됨
            BackfillStatus inactive = buildPendingStatus("000660", "daily_ohlcv");
            BackfillStatus active = buildPendingStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(inactive, active));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 000660은 스킵, 005930은 처리됨(슬롯 1 소비)
            verify(windowExecutor, never()).executeWindow(eq(inactive), any(), any());
            verify(windowExecutor, times(1)).executeWindow(any(), eq(activeStock), eq(session));
        }

        @Test
        @DisplayName(
                "AC-5.4 — 테이블 간 슬롯 비공유: 각 테이블이 독립적으로 perTableCompletionCap 예산 사용 (REQ-BACKFILL-064)")
        void ac5_4_tableCapIndependent_totalExceedsGlobalCapConcept() throws InterruptedException {
            // Arrange: per-table-cap=2, investor_trend 3개 + daily_ohlcv 3개
            // → investor_trend 2개 + daily_ohlcv 2개 = 총 4회 처리 (전역 캡 개념이라면 2회에 그쳤을 것)
            when(properties.getPerTableCompletionCap()).thenReturn(2);

            Stock stock1 = buildDomesticStock("005930");
            Stock stock2 = buildDomesticStock("000660");
            Stock stock3 = buildDomesticStock("003550");
            when(stockRepository.findAllActiveTradable())
                    .thenReturn(List.of(stock1, stock2, stock3));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus it1 = buildPendingStatus("005930", "investor_trend");
            BackfillStatus it2 = buildPendingStatus("000660", "investor_trend");
            BackfillStatus it3 = buildPendingStatus("003550", "investor_trend");
            BackfillStatus ohlcv1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus ohlcv2 = buildPendingStatus("000660", "daily_ohlcv");
            BackfillStatus ohlcv3 = buildPendingStatus("003550", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(it1, it2, it3, ohlcv1, ohlcv2, ohlcv3));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — 총 4회 이상 (각 테이블 독립적으로 2개씩, 합 ≥4)
            verify(windowExecutor, times(4)).executeWindow(any(), any(), eq(session));
        }
    }

    // -----------------------------------------------------------------------
    // AC-4: 예외·인터럽트 격리 (REQ-BACKFILL-055/-070)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-4: 예외·인터럽트 격리")
    class ExceptionIsolation {

        @Test
        @DisplayName(
                "AC-4.1 — 두 번째 윈도우 예외 시 executeWindowOnError 1회·해당 status 루프 종료·다음 status 처리"
                        + " (REQ-BACKFILL-055)")
        void ac4_1_windowException_isolatesStatus_continuesNext() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus first = buildPendingStatus("005930", "daily_ohlcv");
            ReflectionTestUtils.setField(first, "id", 1L);
            BackfillStatus second = buildPendingStatus("005930", "investor_trend");
            ReflectionTestUtils.setField(second, "id", 2L);
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(first, second));

            // first(id=1L): 1회째 성공(IN_PROGRESS 반환), 2회째 예외
            // second(id=2L): 1회째 COMPLETED — id 기반 스텁으로 VT 병렬 레이스 제거
            BackfillStatus firstInProg =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 6, 1));
            BackfillStatus secondCompleted = buildCompletedStatus("005930", "investor_trend");
            when(backfillStatusRepository.findById(eq(1L))).thenReturn(Optional.of(firstInProg));
            when(backfillStatusRepository.findById(eq(2L)))
                    .thenReturn(Optional.of(secondCompleted));

            // first status 두 번째 executeWindow 호출 시 예외
            doThrow(new RuntimeException("KIS 오류 시뮬레이션"))
                    .when(windowExecutor)
                    .executeWindow(eq(firstInProg), any(), any());

            // Act
            orchestrator.run();

            // Assert
            verify(windowExecutor).executeWindowOnError(eq(firstInProg), anyString(), anyBoolean());
            // second status도 처리됨 (별도 테이블 스트림)
            verify(windowExecutor).executeWindow(eq(second), eq(stock), eq(session));
        }

        @Test
        @DisplayName(
                "AC-4.3 — InterruptedException 시 해당 스트림만 종료, 나머지 테이블 스트림은 계속 (REQ-BACKFILL-070)")
        void ac4_3_interruptedStream_otherTablesUnaffected() throws InterruptedException {
            // Arrange: daily_ohlcv(first)는 InterruptedException, investor_trend(second)는 정상
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus first = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus second = buildPendingStatus("005930", "investor_trend");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(first, second));

            // daily_ohlcv 스트림: InterruptedException 발생
            doThrow(new InterruptedException("인터럽트 시뮬레이션"))
                    .when(windowExecutor)
                    .executeWindow(eq(first), any(), any());

            // investor_trend 스트림: 정상 처리
            BackfillStatus secondCompleted = buildCompletedStatus("005930", "investor_trend");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(secondCompleted));

            // Act
            orchestrator.run();

            // Assert — daily_ohlcv 스트림은 중단되었지만 investor_trend 스트림은 정상 처리됨
            // (기존 전역 중단 동작과 다른 핵심 변경점: 한 테이블의 인터럽트가 다른 테이블에 전파되지 않음)
            verify(windowExecutor).executeWindow(eq(second), eq(stock), eq(session));
        }
    }

    // -----------------------------------------------------------------------
    // AC-6: 재사용 불변 회귀 가드
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-6: 재사용 불변 회귀 가드")
    class ReuseInvariants {

        @Test
        @DisplayName("AC-6.1 — 다수 status·다수 윈도우 처리 시 openSession()은 정확히 1회 (REQ-KISGATE-006a)")
        void ac6_1_sessionOpenedExactlyOnce() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus s1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus s2 = buildPendingStatus("005930", "investor_trend");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(s1, s2));

            // 각 status 2 윈도우 후 COMPLETED
            BackfillStatus inProg =
                    buildInProgressStatus("005930", "daily_ohlcv", LocalDate.of(2024, 1, 1));
            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any()))
                    .thenReturn(Optional.of(inProg))
                    .thenReturn(Optional.of(completed))
                    .thenReturn(Optional.of(inProg))
                    .thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert — openSession 정확히 1회 (VT 스트림 기동 전 공유 세션)
            verify(keyLeaseRegistry, times(1)).openSession();
        }

        @Test
        @DisplayName("AC-6.3 — recordProgress 분자·분모 의미 불변 (REQ-BACKFILL-059)")
        void ac6_3_recordProgress_semanticsUnchanged() throws InterruptedException {
            // Arrange — 2개 테이블 각각 1 status, 각 1윈도우 후 COMPLETED
            when(properties.getPerTableCompletionCap()).thenReturn(10);

            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus s1 = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus s2 = buildPendingStatus("005930", "investor_trend");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(s1, s2));

            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            when(backfillStatusRepository.countByStatusAndTargetType(anyString(), anyString()))
                    .thenReturn(5L);
            when(backfillStatusRepository.countByTargetType(anyString())).thenReturn(10L);

            // Act
            orchestrator.run();

            // Assert — recordProgress가 COMPLETED 수 / 전체 수로 호출됨(의미 불변)
            verify(backfillMetrics).recordProgress(5L, 10L);
        }
    }

    // -----------------------------------------------------------------------
    // 기본 흐름 (회귀 유지)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("기본 흐름")
    class BasicFlow {

        @Test
        @DisplayName("run() — seedActiveStocks()를 먼저 호출한다")
        void run_seeds_before_processing() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            BackfillStatus status = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus completed = buildCompletedStatus("005930", "daily_ohlcv");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));
            when(backfillStatusRepository.findById(any())).thenReturn(Optional.of(completed));

            // Act
            orchestrator.run();

            // Assert
            verify(seeder).seedActiveStocks();
            verify(windowExecutor).executeWindow(eq(status), eq(stock), eq(session));
        }

        @Test
        @DisplayName("run() — 활성 종목 없으면 윈도우 실행 없음")
        void run_noActiveStocks_skipsAllItems() throws InterruptedException {
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of());
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            orchestrator.run();

            verify(windowExecutor, never()).executeWindow(any(), any(), any());
        }

        @Test
        @DisplayName("run() — 처리 대상 없으면 (AC-6.5) 윈도우 실행 없음")
        void run_allCompleted_zeroKisCalls() throws InterruptedException {
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of());

            orchestrator.run();

            verify(windowExecutor, never()).executeWindow(any(), any(), any());
        }
    }
}
