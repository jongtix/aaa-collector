package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvFetch;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceFetch;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendFetch;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import com.aaa.collector.stock.supply.ShortSaleFetch;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link StockRangeCoveredGapFiller} 단위 테스트 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-050, AC-13/AC-14
 * 부분).
 *
 * <p>순수 Mockito — {@link BackfillWindowExecutor}의 backward anchor 로직({@code resolveAnchor}/{@code
 * nextAnchor})을 호출하지 않는 독립 경로임을 검증한다(4개 서비스 mock에 오직 {@code fetchWindow}/{@code persistWindow}만
 * 스텁·검증).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StockRangeCoveredGapFiller — STOCK 범위형 정방향 갭 채우기 (SPEC-COLLECTOR-BACKFILL-011)")
class StockRangeCoveredGapFillerTest {

    @Mock private DomesticDailyOhlcvCollectionService domesticOhlcvService;
    @Mock private OverseasDailyOhlcvCollectionService overseasOhlcvService;
    @Mock private InvestorTrendCollectionService investorTrendService;
    @Mock private CreditBalanceCollectionService creditBalanceService;
    @Mock private ShortSaleCollectionService shortSaleService;

    private LeaseSession session;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        session = mock(LeaseSession.class);
        today = LocalDate.of(2026, 7, 15);
    }

    private Stock buildStock(String symbol, Market market) {
        return Stock.builder()
                .symbol(symbol)
                .market(market)
                .assetType(AssetType.STOCK)
                .active(true)
                .build();
    }

    private BackfillStatus buildStatus(String symbol, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(symbol)
                .dataTable(dataTable)
                .status(BackfillStatusType.IN_PROGRESS)
                .staleCount(2)
                .attemptCount(5)
                .lastRowCount(10)
                .build();
    }

    private StockRangeCoveredGapFiller filler(BackfillStatus status, Stock stock) {
        return new StockRangeCoveredGapFiller(
                status,
                stock,
                today,
                session,
                domesticOhlcvService,
                overseasOhlcvService,
                investorTrendService,
                creditBalanceService,
                shortSaleService);
    }

    @Nested
    @DisplayName("daily_ohlcv — 국내: fetchWindow(from=cursor, anchor=stepAnchor) 진짜 범위 재사용")
    class DomesticDailyOhlcv {

        @Test
        @DisplayName("스텝 폭(90일) 이내 — stepAnchor=cursor+90, kept/raw는 result 그대로 매핑")
        void withinStepWidth_stepAnchorIsCursorPlus90() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "daily_ohlcv");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(90);

            DomesticDailyOhlcvFetch fetch =
                    new DomesticDailyOhlcvFetch(List.of(), expectedAnchor, 40, 42);
            when(domesticOhlcvService.fetchWindow(cursor, expectedAnchor, stock, session))
                    .thenReturn(fetch);
            when(domesticOhlcvService.persistWindow(stock, fetch))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 40, 42));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            assertThat(result.kept()).isEqualTo(40);
            assertThat(result.raw()).isEqualTo(42);
            assertThat(result.filledUntil()).isEqualTo(expectedAnchor);
        }

        @Test
        @DisplayName("today 상한 — cursor+90이 today를 초과하면 stepAnchor=today로 캡")
        void exceedsToday_stepAnchorCappedAtToday() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "daily_ohlcv");
            LocalDate cursor = today.minusDays(10); // cursor+90 > today

            DomesticDailyOhlcvFetch fetch = new DomesticDailyOhlcvFetch(List.of(), today, 5, 5);
            when(domesticOhlcvService.fetchWindow(cursor, today, stock, session)).thenReturn(fetch);
            when(domesticOhlcvService.persistWindow(stock, fetch))
                    .thenReturn(new BackfillWindowResult(today, 5, 5));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            assertThat(result.filledUntil()).isEqualTo(today);
            verify(domesticOhlcvService).fetchWindow(cursor, today, stock, session);
        }
    }

    @Nested
    @DisplayName("daily_ohlcv — 해외: fetchWindow(anchor) — anchor만 공급, from은 서비스 내부 관리")
    class OverseasDailyOhlcv {

        @Test
        @DisplayName("해외 시장 종목은 overseasOhlcvService로 라우팅되고 anchor=stepAnchor만 전달")
        void overseasMarket_routesToOverseasService() throws InterruptedException {
            Stock stock = buildStock("AAPL", Market.NASDAQ);
            BackfillStatus status = buildStatus("AAPL", "daily_ohlcv");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(90);

            OverseasDailyOhlcvFetch fetch =
                    new OverseasDailyOhlcvFetch(List.of(), expectedAnchor, 30, 30);
            when(overseasOhlcvService.fetchWindow(expectedAnchor, stock, session))
                    .thenReturn(fetch);
            when(overseasOhlcvService.persistWindow(stock, fetch))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 30, 30));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            assertThat(result.kept()).isEqualTo(30);
            assertThat(result.filledUntil()).isEqualTo(expectedAnchor);
            verify(domesticOhlcvService, never()).fetchWindow(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("investor_trend — fetchWindow(anchor) 단일 anchor 파라미터, 내부 45일 트레일링 재사용")
    class InvestorTrend {

        @Test
        @DisplayName("스텝 폭=45일 — stepAnchor=cursor+45")
        void stepWidthIs45Days() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "investor_trend");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(45);

            InvestorTrendFetch fetch = new InvestorTrendFetch(List.of(), expectedAnchor, 20);
            when(investorTrendService.fetchWindow(expectedAnchor, stock, session))
                    .thenReturn(fetch);
            when(investorTrendService.persistWindow(stock, fetch))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 20));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            assertThat(result.kept()).isEqualTo(20);
            assertThat(result.raw()).isEqualTo(20); // rawRowCount := rowCount (구조적 한계, §2.6)
            assertThat(result.filledUntil()).isEqualTo(expectedAnchor);
        }
    }

    @Nested
    @DisplayName("credit_balance / short_sale_domestic — 비영속 임시 BackfillStatus로 anchor 주입")
    class TransientStatusInjection {

        @Test
        @DisplayName("credit_balance — 임시 status에 targetCode/dataTable/anchor가 올바르게 주입된다")
        void creditBalance_transientStatusCarriesExpectedFields() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "credit_balance");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(45);

            ArgumentCaptor<BackfillStatus> statusCaptor =
                    ArgumentCaptor.forClass(BackfillStatus.class);
            CreditBalanceFetch fetch = new CreditBalanceFetch(List.of(), expectedAnchor, 7);
            when(creditBalanceService.fetchWindow(statusCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(fetch);
            when(creditBalanceService.persistWindow(any(), eq(stock), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 7));

            filler(status, stock).persistStep(cursor);

            BackfillStatus transientStatus = statusCaptor.getValue();
            assertThat(transientStatus.getLastCollectedDate()).isEqualTo(expectedAnchor);
            assertThat(transientStatus.getTargetCode()).isEqualTo("005930");
            assertThat(transientStatus.getDataTable()).isEqualTo("credit_balance");
        }

        @Test
        @DisplayName("credit_balance — 원본 status는 미영향, kept/filledUntil 매핑 확인")
        void creditBalance_originalStatusUnaffected_resultMapped() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "credit_balance");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(45);

            CreditBalanceFetch fetch = new CreditBalanceFetch(List.of(), expectedAnchor, 7);
            when(creditBalanceService.fetchWindow(any(), eq(stock), eq(session))).thenReturn(fetch);
            when(creditBalanceService.persistWindow(any(), eq(stock), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 7));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            // 원본 status는 절대 변경되지 않는다(REQ-CVR-072 — backward walk 의미 불변)
            assertThat(status.getLastCollectedDate()).isNull();
            assertThat(result.kept()).isEqualTo(7);
            assertThat(result.filledUntil()).isEqualTo(expectedAnchor);
        }

        @Test
        @DisplayName("short_sale_domestic — 스텝 폭=90일, 임시 status anchor 주입")
        void shortSaleDomestic_stepWidth90_injectsTransientStatusAnchor()
                throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "short_sale_domestic");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(90);

            ArgumentCaptor<BackfillStatus> statusCaptor =
                    ArgumentCaptor.forClass(BackfillStatus.class);
            ShortSaleFetch fetch = new ShortSaleFetch(List.of(), expectedAnchor, 3);
            when(shortSaleService.fetchWindow(statusCaptor.capture(), eq(stock), eq(session)))
                    .thenReturn(fetch);
            when(shortSaleService.persistWindow(any(), eq(stock), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 3));

            CoveredFillResult result = filler(status, stock).persistStep(cursor);

            assertThat(statusCaptor.getValue().getLastCollectedDate()).isEqualTo(expectedAnchor);
            assertThat(result.kept()).isEqualTo(3);
            assertThat(result.filledUntil()).isEqualTo(expectedAnchor);
        }
    }

    @Nested
    @DisplayName("인터럽트 전파")
    class InterruptPropagation {

        @Test
        @DisplayName("fetchWindow InterruptedException — 인터럽트 플래그 복원 + 언체크 예외로 전파")
        void interruptedException_restoresFlagAndWrapsUnchecked() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "daily_ohlcv");
            LocalDate cursor = LocalDate.of(2026, 1, 1);

            when(domesticOhlcvService.fetchWindow(any(), any(), eq(stock), eq(session)))
                    .thenThrow(new InterruptedException("시뮬레이션"));

            try {
                assertThatThrownBy(() -> filler(status, stock).persistStep(cursor))
                        .isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
            } finally {
                assertThat(Thread.interrupted()).isTrue(); // 플래그 복원 확인 후 소비(다음 테스트 오염 방지)
            }
        }
    }

    @Test
    @DisplayName("커버-추적 비대상 data_table — IllegalStateException")
    void untrackedDataTable_throwsIllegalState() {
        Stock stock = buildStock("005930", Market.KOSPI);
        BackfillStatus status = buildStatus("005930", "corporate_events");

        assertThatThrownBy(() -> filler(status, stock).persistStep(LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
