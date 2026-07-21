package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link StockCoveredGapWalkRunner} 단위 테스트 (aaa-infra#112, SPEC-COLLECTOR-BACKFILL-012 TASK-001).
 *
 * <p>매일 02:00 KST 백필 크론 발화 시점에는 미국(NYSE/NASDAQ/AMEX) 장이 아직 전일(ET) 거래 중이라, KST 벽시계 {@code today}를
 * 그대로 상한으로 주입하면 KIS가 미확정(장중) 부분바를 반환하고 {@code INSERT IGNORE}로 영구 저장되어 확정바 업데이트가 영구 차단된다. 이 테스트는
 * 진입점({@link StockCoveredGapWalkRunner#resolveCap(Stock)})에서 해외 종목은 ET 전일, 국내 종목은 KST 당일을 상한으로 산출함을
 * 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockCoveredGapWalkRunner — 해외/국내 상한 캡 (aaa-infra#112)")
class StockCoveredGapWalkRunnerTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private CoveredRangeService coveredRangeService;
    @Mock private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @Mock private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private CreditBalanceCollectionService creditBalanceService;
    @Mock private ShortSaleCollectionService shortSaleService;

    private LeaseSession session;

    @BeforeEach
    void setUp() {
        session = mock(LeaseSession.class);
    }

    private StockCoveredGapWalkRunner runner(Clock clock) {
        return new StockCoveredGapWalkRunner(
                backfillStatusRepository,
                coveredRangeService,
                domesticOhlcvService,
                overseasOhlcvService,
                investorTrendService,
                creditBalanceService,
                shortSaleService,
                clock);
    }

    private Stock buildStock(String symbol, Market market) {
        return Stock.builder()
                .symbol(symbol)
                .market(market)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("resolveCap — 여름(EDT, UTC-4) 케이스")
    class SummerEdt {

        // 2026-07-20T17:00:00Z == 2026-07-20 13:00 EDT(장중) == 2026-07-21 02:00 KST(크론 발화 시각)
        private final Clock clock =
                Clock.fixed(Instant.parse("2026-07-20T17:00:00Z"), ZoneId.of("America/New_York"));

        @Test
        @DisplayName("해외 종목 — ET 전일(2026-07-19) 상한, ET 당일(장중) 미포함")
        void overseas_capIsEtYesterday() {
            Stock stock = buildStock("AAPL", Market.NASDAQ);

            LocalDate cap = runner(clock).resolveCap(stock);

            assertThat(cap).isEqualTo(LocalDate.of(2026, 7, 19));
            assertThat(cap).isNotEqualTo(LocalDate.of(2026, 7, 20));
        }

        @Test
        @DisplayName("국내 종목 — KST 당일(2026-07-21) 상한, 기존 KST 벽시계 동작 회귀 없음")
        void domestic_capIsKstToday() {
            Stock stock = buildStock("005930", Market.KOSPI);

            LocalDate cap = runner(clock).resolveCap(stock);

            assertThat(cap).isEqualTo(LocalDate.of(2026, 7, 21));
        }
    }

    @Nested
    @DisplayName("resolveCap — 겨울(EST, UTC-5) 케이스")
    class WinterEst {

        // 2026-01-19T17:00:00Z == 2026-01-19 12:00 EST(장중) == 2026-01-20 02:00 KST(크론 발화 시각)
        private final Clock clock =
                Clock.fixed(Instant.parse("2026-01-19T17:00:00Z"), ZoneId.of("America/New_York"));

        @Test
        @DisplayName("해외 종목 — ET 전일(2026-01-18) 상한, ET 당일(장중) 미포함")
        void overseas_capIsEtYesterday() {
            Stock stock = buildStock("AAPL", Market.NYSE);

            LocalDate cap = runner(clock).resolveCap(stock);

            assertThat(cap).isEqualTo(LocalDate.of(2026, 1, 18));
            assertThat(cap).isNotEqualTo(LocalDate.of(2026, 1, 19));
        }

        @Test
        @DisplayName("국내 종목 — KST 당일(2026-01-20) 상한")
        void domestic_capIsKstToday() {
            Stock stock = buildStock("005930", Market.KOSDAQ);

            LocalDate cap = runner(clock).resolveCap(stock);

            assertThat(cap).isEqualTo(LocalDate.of(2026, 1, 20));
        }

        @Test
        @DisplayName("AMEX도 해외 시장으로 취급되어 ET 전일 상한이 적용된다")
        void amex_treatedAsOverseas() {
            Stock stock = buildStock("SPY", Market.AMEX);

            LocalDate cap = runner(clock).resolveCap(stock);

            assertThat(cap).isEqualTo(LocalDate.of(2026, 1, 18));
        }
    }

    @Nested
    @DisplayName("runFor — 시장별 캡이 filler/walkGapForward 양쪽에 동일하게 주입된다")
    class RunForIntegration {

        // 2026-07-20T17:00:00Z == 2026-07-21 02:00 KST
        private final Clock clock =
                Clock.fixed(Instant.parse("2026-07-20T17:00:00Z"), ZoneId.of("America/New_York"));

        @Test
        @DisplayName("해외 종목 statuses만 있을 때 walkGapForward에 ET 전일 상한이 전달된다")
        void overseasOnly_walkGapForwardReceivesEtYesterday() {
            Stock stock = buildStock("AAPL", Market.NASDAQ);
            BackfillStatus status = buildStatus("AAPL", "daily_ohlcv");
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "daily_ohlcv"))
                    .thenReturn(List.of(status));
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "investor_trend"))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "credit_balance"))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "short_sale_domestic"))
                    .thenReturn(List.of());

            runner(clock).runFor(Map.of("AAPL", stock), session);

            verify(coveredRangeService)
                    .walkGapForward(
                            any(),
                            any(),
                            org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 7, 19)));
            verify(coveredRangeService, never())
                    .walkGapForward(
                            any(),
                            any(),
                            org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 7, 20)));
        }

        @Test
        @DisplayName("국내 종목 statuses만 있을 때 walkGapForward에 KST 당일 상한이 전달된다")
        void domesticOnly_walkGapForwardReceivesKstToday() {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "daily_ohlcv");
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "daily_ohlcv"))
                    .thenReturn(List.of(status));
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "investor_trend"))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "credit_balance"))
                    .thenReturn(List.of());
            when(backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            "STOCK", "short_sale_domestic"))
                    .thenReturn(List.of());

            runner(clock).runFor(Map.of("005930", stock), session);

            verify(coveredRangeService)
                    .walkGapForward(
                            any(),
                            any(),
                            org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 7, 21)));
        }
    }

    private BackfillStatus buildStatus(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status(BackfillStatusType.IN_PROGRESS)
                .staleCount(0)
                .attemptCount(0)
                .lastRowCount(0)
                .build();
    }
}
