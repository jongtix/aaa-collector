package com.aaa.collector.market.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.observability.BackfillDensityMetrics;
import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoverageRefresher — 일봉 커버리지 일1회 계산 (REQ-WM-010/011)")
class CoverageRefresherTest {

    @Mock private MarketSessionGate marketSessionGate;
    @Mock private UsMarketSessionGate usMarketSessionGate;
    @Mock private StockRepository stockRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private CoverageMetrics coverageMetrics;
    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private BackfillDensityMetrics densityMetrics;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private CoverageRefresher refresher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubTransactionTemplate() {
        // executeWithoutResult(Consumer<TransactionStatus>)를 즉시 동기 실행 — 실제 DB 트랜잭션 없이 콜백만 구동한다.
        Mockito.lenient()
                .doAnswer(
                        invocation -> {
                            Consumer<TransactionStatus> callback = invocation.getArgument(0);
                            callback.accept(Mockito.mock(TransactionStatus.class));
                            return null;
                        })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    private static Stock stock(long id, Market market) {
        return Stock.builder()
                .symbol("SYM" + id)
                .nameKo("종목" + id)
                .market(market)
                .assetType(AssetType.STOCK)
                .listedDate(LocalDate.of(2015, 1, 1))
                .build();
    }

    @Nested
    @DisplayName("KRX 커버리지")
    class KrxCoverage {

        @Test
        @DisplayName("expected watermark 없으면(null) 계산을 스킵한다")
        void skipsWhenExpectedIsNull() {
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(null);

            refresher.refreshKrxCoverage();

            verify(coverageMetrics, never()).setRatio(any(), any(Double.class));
        }

        @Test
        @DisplayName("유니버스 대비 커버리지 비율을 계산해 게이지에 반영한다")
        void computesRatioAndSetsGauge() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            List<Stock> universe =
                    List.of(
                            stock(1, Market.KOSPI),
                            stock(2, Market.KOSPI),
                            stock(3, Market.KOSDAQ));
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(universe);
            when(dailyOhlcvRepository.countDistinctStockIdsByTradeDateAndStockIdIn(
                            eq(expected), anyCollection()))
                    .thenReturn(2L);

            refresher.refreshKrxCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 2.0 / 3.0);
        }

        @Test
        @DisplayName("유니버스가 비면 0.0으로 설정한다 (0 나눗셈 방지)")
        void emptyUniverse_setsZero() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of());

            refresher.refreshKrxCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.0);
        }
    }

    @Nested
    @DisplayName("US 커버리지")
    class UsCoverage {

        @Test
        @DisplayName("expected watermark 없으면(null) 계산을 스킵한다")
        void skipsWhenExpectedIsNull() {
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);

            refresher.refreshUsCoverage();

            verify(coverageMetrics, never()).setRatio(any(), any(Double.class));
        }

        @Test
        @DisplayName("유니버스 대비 커버리지 비율을 계산해 게이지에 반영한다")
        void computesRatioAndSetsGauge() {
            LocalDate expected = LocalDate.of(2026, 7, 3);
            List<Stock> universe = List.of(stock(1, Market.NASDAQ), stock(2, Market.NYSE));
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(expected);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(universe);
            when(dailyOhlcvRepository.countDistinctStockIdsByTradeDateAndStockIdIn(
                            eq(expected), anyCollection()))
                    .thenReturn(2L);

            refresher.refreshUsCoverage();

            verify(coverageMetrics).setRatio(WatermarkSeries.DAILY_OHLCV_US, 1.0);
        }
    }

    @Nested
    @DisplayName("밀도 게이지 A/B (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155, AC-7/AC-8/AC-9)")
    class DensityGauges {

        @Test
        @DisplayName("AC-7: 미국은 항상 신뢰 하한 존재(벽) — 하한 미달 종목만 게이지 A 카운트")
        void us_belowFloor_countsOnlyBelowFloorStocks() {
            Stock nvda = stock(1, Market.NASDAQ); // listed_date=2015-01-01 (테스트 fixture 고정값)
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(nvda));
            // MIN(trade_date) > floor(listed_date=2015-01-01) → 하한 미달
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        nvda.getId(),
                                        LocalDate.of(2016, 1, 1),
                                        LocalDate.of(2020, 1, 1),
                                        100
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(List.of(LocalDate.of(2016, 1, 1), LocalDate.of(2020, 1, 1)));

            refresher.refreshUsCoverage();

            verify(densityMetrics).setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_US, 1L);
        }

        @Test
        @DisplayName("EC-5: 국내 미검증 레거시(검증 기준선 없음) — 게이지 A 모집단 제외(영구 오탐 차단)")
        void domestic_noVerifiedBaseline_excludedFromGaugeA() {
            Stock samsung = stock(1, Market.KOSPI);
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of(samsung));
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        samsung.getId(),
                                        LocalDate.of(1990, 3, 5),
                                        LocalDate.of(2020, 1, 1),
                                        1000
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(List.of(LocalDate.of(1990, 3, 5)));
            when(backfillStatusRepository.findVerifiedBaseline(any(), any(), any()))
                    .thenReturn(java.util.Optional.empty()); // 검증 기준선 없음

            refresher.refreshKrxCoverage();

            verify(densityMetrics).setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 0L);
        }

        @Test
        @DisplayName("AC-8: 캘린더 합집합 대비 구간 내 부재 거래일 있는 종목 — 게이지 B 카운트")
        void internalGap_counted() {
            Stock nvda = stock(1, Market.NASDAQ);
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(nvda));
            // 구간 [1/1, 1/4] 캘린더 4일 존재, 보유 3행 → 구멍 1
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        nvda.getId(),
                                        LocalDate.of(2020, 1, 1),
                                        LocalDate.of(2020, 1, 4),
                                        3
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(
                            List.of(
                                    LocalDate.of(2020, 1, 1),
                                    LocalDate.of(2020, 1, 2),
                                    LocalDate.of(2020, 1, 3),
                                    LocalDate.of(2020, 1, 4)));

            refresher.refreshUsCoverage();

            verify(densityMetrics).setInternalGapCount(WatermarkSeries.DAILY_OHLCV_US, 1L);
        }

        @Test
        @DisplayName("AC-9: 유니버스가 비면 게이지 A/B 모두 0으로 설정한다")
        void emptyUniverse_setsBothGaugesZero() {
            when(marketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveDomesticTradable()).thenReturn(List.of());

            refresher.refreshKrxCoverage();

            verify(densityMetrics).setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 0L);
            verify(densityMetrics).setInternalGapCount(WatermarkSeries.DAILY_OHLCV_KRX, 0L);
        }
    }

    @Nested
    @DisplayName(
            "listed_date 하향 보정 + 표적 재처리 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-159/-160, AC-16/AC-17,"
                    + " EC-14)")
    class ListedDateCorrectionAndTargetedReset {

        @Test
        @DisplayName("AC-16(a): MIN(trade_date) < listed_date → managed 인스턴스에 하향 보정 적용(원자적 트랜잭션)")
        void minBeforeListedDate_correctsManagedInstance() {
            Stock pltr = stock(1, Market.NASDAQ); // listed_date=2015-01-01 fixture
            Stock managedPltr = stock(1, Market.NASDAQ);
            LocalDate trueMin = LocalDate.of(2010, 9, 30); // 과대평가 사례 — 진짜 IPO가 더 이름
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(pltr));
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        pltr.getId(), trueMin, LocalDate.of(2020, 1, 1), 100
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(List.of(trueMin));
            when(stockRepository.findById(pltr.getId())).thenReturn(Optional.of(managedPltr));

            refresher.refreshUsCoverage();

            // managed 인스턴스가 실제로 하향 보정됨(영속 대상)
            org.assertj.core.api.Assertions.assertThat(managedPltr.getListedDate())
                    .isEqualTo(trueMin);
            // 호출자 detached 인스턴스도 이번 cron 실행 내 즉시 갱신되어 게이지 계산에 반영됨
            org.assertj.core.api.Assertions.assertThat(pltr.getListedDate()).isEqualTo(trueMin);
        }

        @Test
        @DisplayName("AC-17: 보정된 종목의 GROUP_A daily_ohlcv backfill_status만 표적 리셋")
        void correctedStock_resetsTargetedBackfillStatus() {
            Stock pltr = stock(1, Market.NASDAQ);
            Stock managedPltr = stock(1, Market.NASDAQ);
            LocalDate trueMin = LocalDate.of(2010, 9, 30);
            BackfillStatus completedStatus =
                    BackfillStatus.builder()
                            .targetType("STOCK")
                            .targetCode("SYM1")
                            .dataTable("daily_ohlcv")
                            .status(BackfillStatusType.COMPLETED)
                            .build();
            BackfillStatus spyStatus = Mockito.spy(completedStatus);
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(pltr));
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        pltr.getId(), trueMin, LocalDate.of(2020, 1, 1), 100
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(List.of(trueMin));
            when(stockRepository.findById(pltr.getId())).thenReturn(Optional.of(managedPltr));
            when(backfillStatusRepository.findByTargetTypeAndTargetCodeAndDataTable(
                            "STOCK", "SYM1", "daily_ohlcv"))
                    .thenReturn(Optional.of(spyStatus));

            refresher.refreshUsCoverage();

            verify(spyStatus).resetForReprocess();
        }

        @Test
        @DisplayName("AC-16(c)/(d): 정상(MIN>listed_date) 또는 데이터 없음 — 상향 보정·표적 리셋 모두 미발생")
        void normalOrNoData_neverCorrectsOrResets() {
            Stock stock = stock(1, Market.NASDAQ); // listed_date=2015-01-01
            when(usMarketSessionGate.computeExpectedTradeDate()).thenReturn(null);
            when(stockRepository.findAllActiveOverseasTradable()).thenReturn(List.of(stock));
            // MIN > listed_date(2015-01-01) — 정상 케이스, 상향 보정 없음
            when(dailyOhlcvRepository.findMinMaxCountByStockIds(anyCollection()))
                    .thenReturn(
                            List.<Object[]>of(
                                    new Object[] {
                                        stock.getId(),
                                        LocalDate.of(2016, 1, 1),
                                        LocalDate.of(2020, 1, 1),
                                        100
                                    }));
            when(dailyOhlcvRepository.findDistinctTradeDatesByStockIds(anyCollection()))
                    .thenReturn(List.of(LocalDate.of(2016, 1, 1)));

            refresher.refreshUsCoverage();

            verify(stockRepository, never()).findById(any());
            verify(backfillStatusRepository, never())
                    .findByTargetTypeAndTargetCodeAndDataTable(any(), any(), any());
            org.assertj.core.api.Assertions.assertThat(stock.getListedDate())
                    .isEqualTo(LocalDate.of(2015, 1, 1)); // 불변
        }
    }
}
