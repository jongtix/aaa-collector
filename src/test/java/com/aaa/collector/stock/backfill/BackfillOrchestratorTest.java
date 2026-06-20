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
import java.util.List;
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

        // 기본 throttle 설정 (lenient — 일부 테스트는 throttle 코드에 도달하지 않음)
        when(properties.getPerRunStockCap()).thenReturn(50);
        when(properties.getPerRunWindowCap()).thenReturn(100);
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

    @Nested
    @DisplayName("기본 흐름")
    class BasicFlow {

        @Test
        @DisplayName("run() — seedActiveStocks()를 먼저 호출한다")
        void run_seeds_then_processesItems() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            BackfillStatus status = buildPendingStatus("005930", "daily_ohlcv");

            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(status));

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

    @Nested
    @DisplayName("활성 종목 필터 (AC-7.4)")
    class ActiveStockFilter {

        @Test
        @DisplayName("run() — 비활성(맵에 없는) 종목 항목은 windowExecutor.executeWindow 호출 없음")
        void run_skipsInactiveStockItems() throws InterruptedException {
            // Arrange — 활성 종목에 005930만 등록, 000660은 비활성
            Stock stock005930 = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock005930));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus inactive = buildPendingStatus("000660", "daily_ohlcv");
            BackfillStatus active = buildPendingStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(inactive, active));

            // Act
            orchestrator.run();

            // Assert — 000660 항목은 스킵, 005930만 처리
            verify(windowExecutor, times(1))
                    .executeWindow(eq(active), eq(stock005930), eq(session));
            verify(windowExecutor, never()).executeWindow(eq(inactive), any(), any());
        }
    }

    @Nested
    @DisplayName("throttle (AC-7.2b)")
    class Throttle {

        @Test
        @DisplayName("run() — perRunWindowCap 도달 시 후속 항목을 처리하지 않는다")
        void run_respectsPerRunWindowCap() throws InterruptedException {
            // Arrange — windowCap=2, 항목 3개
            when(properties.getPerRunWindowCap()).thenReturn(2);

            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            List<BackfillStatus> items =
                    List.of(
                            buildPendingStatus("005930", "daily_ohlcv"),
                            buildPendingStatus("005930", "investor_trend"),
                            buildPendingStatus("005930", "credit_balance"));
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(items);

            // Act
            orchestrator.run();

            // Assert — 2개만 처리
            verify(windowExecutor, times(2)).executeWindow(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("예외 격리 (AC-7.1)")
    class ExceptionIsolation {

        @Test
        @DisplayName("run() — 첫 항목 예외 시 executeWindowOnError 호출 후 두 번째 항목도 처리한다")
        void run_itemExceptionIsolation_continuesNextItem() throws InterruptedException {
            // Arrange
            Stock stock = buildDomesticStock("005930");
            when(stockRepository.findAllActiveTradable()).thenReturn(List.of(stock));
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of());

            BackfillStatus first = buildPendingStatus("005930", "daily_ohlcv");
            BackfillStatus second = buildPendingStatus("005930", "investor_trend");
            when(backfillStatusRepository.findByStatusInAndTargetTypeOrderById(any(), anyString()))
                    .thenReturn(List.of(first, second));

            doThrow(new RuntimeException("KIS 오류 시뮬레이션"))
                    .when(windowExecutor)
                    .executeWindow(eq(first), any(), any());

            // Act
            orchestrator.run();

            // Assert — 첫 항목 오류 기록 후 두 번째 항목도 처리됨
            verify(windowExecutor).executeWindowOnError(eq(first), anyString(), anyBoolean());
            verify(windowExecutor).executeWindow(eq(second), eq(stock), eq(session));
        }
    }
}
