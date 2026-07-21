package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link BackfillOrchestrator} — STOCK 범위형 정방향 갭 walk 배선 검증 (SPEC-COLLECTOR-BACKFILL-011
 * REQ-CVR-011, -041, -060, AC-13/AC-14 부분).
 *
 * <p>{@link StockCoveredGapWalkRunner}를 mock으로 대체해 배선(호출 여부·시점·인자)만 검증한다 — 필러 자체의 kept/raw 매핑은
 * {@link StockRangeCoveredGapFillerTest}가 이미 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackfillOrchestrator — STOCK 커버-추적 배선 (SPEC-COLLECTOR-BACKFILL-011)")
class BackfillOrchestratorCoveredTrackingTest {

    @Mock private BackfillStatusSeeder seeder;
    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private BackfillWindowExecutor windowExecutor;
    @Mock private KeyLeaseRegistry keyLeaseRegistry;
    @Mock private StockRepository stockRepository;
    @Mock private BackfillProperties properties;
    @Mock private BackfillMetrics backfillMetrics;
    @Mock private StockCoveredGapWalkRunner stockCoveredGapWalkRunner;

    @InjectMocks private BackfillOrchestrator orchestrator;

    private LeaseSession session;

    @BeforeEach
    void setUp() {
        session = mock(LeaseSession.class);
        when(keyLeaseRegistry.openSession()).thenReturn(session);
        when(properties.getPerTableCompletionCap()).thenReturn(10);
        when(properties.getMaxWindowsPerTarget()).thenReturn(120);
    }

    private Stock buildStock(String symbol) {
        return Stock.builder()
                .symbol(symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private BackfillStatus buildCompletedStatus(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status(BackfillStatusType.COMPLETED)
                .build();
    }

    @Test
    @DisplayName(
            "AC-14 — backward walk 처리 대상이 없어도(pending 빈 목록) STOCK 갭 walk는 항상 시도된다 (REQ-CVR-041)")
    void pendingEmpty_stockCoveredGapWalkStillRuns() {
        // Arrange — backward walk 대상 없음(모두 COMPLETED 등)이지만 활성 종목은 존재
        Stock stock = buildStock("005930");
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
        when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                .thenReturn(List.of());

        // Act
        orchestrator.run();

        // Assert — 처리 대상이 없어도(AC-6.5) 갭 walk 러너는 정확히 1회, 활성 종목 맵과 함께 호출된다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Stock>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(stockCoveredGapWalkRunner, times(1)).runFor(mapCaptor.capture(), eq(session));
        assertThat(mapCaptor.getValue()).containsEntry("005930", stock);
    }

    @Test
    @DisplayName("전 키 사망(session.isEmpty()) — STOCK 갭 walk도 함께 skip (REQ-BACKFILL-137과 동일 가드)")
    void allKeysDead_gapWalkSkipped() {
        // Arrange
        when(session.isEmpty()).thenReturn(true);

        // Act
        orchestrator.run();

        // Assert
        verify(stockCoveredGapWalkRunner, never()).runFor(any(), any());
    }

    @Test
    @DisplayName("활성 종목 없음 — STOCK 갭 walk도 함께 skip")
    void noActiveStocks_gapWalkSkipped() {
        // Arrange
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of());
        when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

        // Act
        orchestrator.run();

        // Assert
        verify(stockCoveredGapWalkRunner, never()).runFor(any(), any());
    }

    @Test
    @DisplayName("backward walk 처리 대상이 있는 정상 회차에서도 갭 walk는 정확히 1회 호출된다")
    void pendingNonEmpty_stockCoveredGapWalkRunsOnce() {
        // Arrange
        Stock stock = buildStock("005930");
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
        when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
        when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                .thenReturn(List.of(buildCompletedStatus("005930", "daily_ohlcv")));
        when(backfillStatusRepository.findById(any()))
                .thenReturn(Optional.of(buildCompletedStatus("005930", "daily_ohlcv")));

        // Act
        orchestrator.run();

        // Assert
        verify(stockCoveredGapWalkRunner, times(1)).runFor(any(), eq(session));
    }

    @Test
    @DisplayName(
            "REQ-ASSETSCOPE-006 — ETN·COMMODITY가 STOCK과 함께 활성 대상 맵에 포함되어 정방향 갭 walk에 자동"
                    + " 편입된다(코드 변경 없이, SPEC-COLLECTOR-ASSETSCOPE-001)")
    void etnAndCommodity_includedInActiveMapForForwardGapWalk() {
        // Arrange — findAllActiveTradable()가 STOCK·ETN·COMMODITY 혼재 목록을 반환하면
        // 오케스트레이터는 자산유형을 구분하지 않고 전부 활성 맵에 담아 갭 walk 러너에 전달한다.
        Stock stock =
                Stock.builder()
                        .symbol("005930")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(true)
                        .build();
        Stock etn =
                Stock.builder()
                        .symbol("Q760009")
                        .market(Market.KOSPI)
                        .assetType(AssetType.ETN)
                        .active(true)
                        .build();
        Stock commodity =
                Stock.builder()
                        .symbol("M04020000")
                        .market(Market.KOSPI)
                        .assetType(AssetType.COMMODITY)
                        .active(true)
                        .build();
        when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock, etn, commodity));
        when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
        when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                .thenReturn(List.of());

        // Act
        orchestrator.run();

        // Assert — STOCK·ETN·COMMODITY 전부 활성 맵에 포함되어 갭 walk 러너에 전달됨(회귀 없음)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Stock>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(stockCoveredGapWalkRunner, times(1)).runFor(mapCaptor.capture(), eq(session));
        assertThat(mapCaptor.getValue())
                .containsEntry("005930", stock)
                .containsEntry("Q760009", etn)
                .containsEntry("M04020000", commodity);
    }
}
