package com.aaa.collector.stock.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    @DisplayName(
            "investor_trend — fetchWindow(anchor) 단일 anchor 파라미터, 내부 필터 lookback(45일)과 분리된 35일 스텝 폭"
                    + " 재사용")
    class InvestorTrend {

        @Test
        @DisplayName(
                "스텝 폭=35일(SINGLE_ANCHOR_STEP_CALENDAR_DAYS, REQ-CVR-074/075) — stepAnchor=cursor+35")
        void stepWidthIs35Days() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "investor_trend");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(35);

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
        @DisplayName("credit_balance — 임시 status에 targetCode/dataTable/anchor가 올바르게 주입된다(스텝 폭 35일)")
        void creditBalance_transientStatusCarriesExpectedFields() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "credit_balance");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(35);

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
        @DisplayName("credit_balance — 원본 status는 미영향, kept/filledUntil 매핑 확인(스텝 폭 35일)")
        void creditBalance_originalStatusUnaffected_resultMapped() throws InterruptedException {
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "credit_balance");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(35);

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

    @Nested
    @DisplayName("스텝 폭 안전 불변식 회귀 가드 (REQ-CVR-073, plan.md §9a.1, [D4])")
    class StepWidthSafetyInvariant {

        // StockRangeCoveredGapFiller.SINGLE_ANCHOR_STEP_CALENDAR_DAYS와 동일 값 — 위 InvestorTrend
        // stepWidthIs35Days()가 실제 filler 동작으로 이 값을 재확인한다(35=cursor→stepAnchor 오프셋 실측).
        private static final int SINGLE_ANCHOR_STEP_CALENDAR_DAYS = 35;

        // credit_balance(FHPST04760000)·investor_trend(FHPTJ04160001) 단일 anchor 1콜 API 반환 용량(§1.4
        // 실측
        // "정확히 30 거래일" — 자체 실측 + KIS 공식 SDK docstring 이중 근거).
        private static final int SINGLE_ANCHOR_MAX_TRADING_DAYS = 30;

        /** L 연속 달력일 중 공휴일 0(최대 밀도) worst case의 최대 평일(월~금) 수. */
        private static int maxWeekdaysInSpan(LocalDate start, int calendarDays) {
            int count = 0;
            for (int i = 0; i < calendarDays; i++) {
                DayOfWeek dow = start.plusDays(i).getDayOfWeek();
                if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                    count++;
                }
            }
            return count;
        }

        @Test
        @DisplayName(
                "AC-18 — 스텝 폭 35일(claimed 구간 L=36 달력일)의 공휴일 0 worst case 거래일 수가 API 용량 30 이하(회귀 가드)")
        void stepWidthWorstCaseTradingDays_neverExceedsApiCapacity() {
            // Arrange — 최대 밀도 worst case: 월요일 시작 L=36 연속 달력일(공휴일 0)
            LocalDate mondayStart = LocalDate.of(2026, 1, 5); // Monday
            int calendarSpan =
                    SINGLE_ANCHOR_STEP_CALENDAR_DAYS
                            + 1; // claimed [cursor, cursor+stepDays] inclusive

            // Act
            int worstCaseTradingDays = maxWeekdaysInSpan(mondayStart, calendarSpan);

            // Assert — ① worst case 거래일 수 ≤ 30, ② 회귀 가드: 상수 값이 상한(이론적 ≤41, 대안 검산 ≤39)을 넘으면 이
            // 어서션이 실패한다
            assertThat(worstCaseTradingDays).isLessThanOrEqualTo(SINGLE_ANCHOR_MAX_TRADING_DAYS);
        }

        @Test
        @DisplayName(
                "[D4] 스텝 폭 축소(45→35)로 900달력일 고정 갭의 회차 수가 20→26으로 증가(≈45/35배, REQ-CVR-042 비상충,"
                        + " plan §9a.1)")
        void stepCountIncreaseRatio_fixedGap900CalendarDays() {
            // Arrange
            int gapDays = 900;
            int oldStepDays = 45;
            int newStepDays = SINGLE_ANCHOR_STEP_CALENDAR_DAYS;

            // Act
            int oldSteps = (int) Math.ceil(gapDays / (double) oldStepDays);
            int newSteps = (int) Math.ceil(gapDays / (double) newStepDays);

            // Assert
            assertThat(oldSteps).isEqualTo(20);
            assertThat(newSteps).isEqualTo(26);
            assertThat((double) newSteps / oldSteps)
                    .isCloseTo(oldStepDays / (double) newStepDays, within(0.05));
        }
    }

    @Nested
    @DisplayName("범위형 소스 스텝 폭 API 용량 마진 검증 (REQ-CVR-073, -075a, TASK-011)")
    class RangeTypeStepWidthMarginVerification {

        // daily_ohlcv 단일 콜 최대 반환 행수 — REQ-BACKFILL-013 100건-cap 종료 게이트와 동일 근거(GROUP_A).
        private static final int DAILY_OHLCV_MAX_ROWS_PER_CALL = 100;

        /** L 연속 달력일 중 공휴일 0(최대 밀도) worst case의 최대 평일(월~금) 수 — StepWidthSafetyInvariant와 동일 계산. */
        private static int maxWeekdaysInSpan(LocalDate start, int calendarDays) {
            int count = 0;
            for (int i = 0; i < calendarDays; i++) {
                DayOfWeek dow = start.plusDays(i).getDayOfWeek();
                if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                    count++;
                }
            }
            return count;
        }

        @Test
        @DisplayName(
                "AC-20 — daily_ohlcv 스텝 폭 90일 불변 + 90달력일(worst case 거래일 수) < 100행 API cap 마진 명시 검증"
                        + "(REQ-CVR-075a, 침묵 가정 아님)")
        void dailyOhlcv_stepWidth90Unchanged_marginUnderApiCap() throws InterruptedException {
            // Arrange — daily_ohlcv 스텝 폭(90일)을 실동작으로 재확인하고, 90달력일 worst case 거래일 수를 산정한다.
            // daily_ohlcv는 GROUP_A 100건-cap 종료 게이트로 별도
            // 관리되므로(StockRangeCoveredGapFiller.STEP_DAYS_WIDE
            // Javadoc), 90달력일 구간의 최대 거래일 수가 이 100행 cap 미만이어야 over-claim이 없다.
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "daily_ohlcv");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(90);
            DomesticDailyOhlcvFetch fetch =
                    new DomesticDailyOhlcvFetch(List.of(), expectedAnchor, 1, 1);
            when(domesticOhlcvService.fetchWindow(cursor, expectedAnchor, stock, session))
                    .thenReturn(fetch);
            when(domesticOhlcvService.persistWindow(stock, fetch))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 1, 1));

            // Act
            CoveredFillResult result = filler(status, stock).persistStep(cursor);
            int actualStepDays = (int) ChronoUnit.DAYS.between(cursor, result.filledUntil());
            int worstCaseTradingDays =
                    maxWeekdaysInSpan(LocalDate.of(2026, 1, 5), actualStepDays + 1);

            // Assert — ① daily_ohlcv 스텝 폭 90일 불변, ② 90달력일 worst case 거래일 수 < 100행 cap(마진 명시)
            assertThat(actualStepDays).isEqualTo(90);
            assertThat(worstCaseTradingDays).isLessThan(DAILY_OHLCV_MAX_ROWS_PER_CALL);
        }

        @Test
        @DisplayName(
                "AC-20 — short_sale_domestic 스텝 폭 90일 불변, 진짜 범위 조회(FID_INPUT_DATE_1+DATE_2)라 구조적으로"
                        + " over-claim 불가(무cap, 실측 61행/90일은 참고용 경험치일 뿐 용량 한계 아님)")
        void shortSaleDomestic_stepWidth90Unchanged_noCapStructurallySafe()
                throws InterruptedException {
            // Arrange — short_sale_domestic(TR FHPST04830000)은 [from,to] 범위 전체를 반환하는 진짜 범위 조회다.
            // credit_balance·investor_trend처럼 "1콜당 고정 N행 상한"이 없으므로, claimed 구간(90달력일)과 반환 구간이
            // 항상 일치해 REQ-CVR-073(스텝 폭 ≤ API 용량)을 구조적으로 자동 충족한다(over-claim 불가능). 90일 창에서
            // 실측된 61행(api-specs/kis 실측)은 "용량 한계"가 아니라 해당 기간의 실제 거래일 수를 반영한 경험치일 뿐이다 —
            // 이 구조적 근거를 침묵 가정하지 않고 테스트·주석으로 명시한다(REQ-CVR-075a).
            Stock stock = buildStock("005930", Market.KOSPI);
            BackfillStatus status = buildStatus("005930", "short_sale_domestic");
            LocalDate cursor = LocalDate.of(2026, 1, 1);
            LocalDate expectedAnchor = cursor.plusDays(90);
            ShortSaleFetch fetch = new ShortSaleFetch(List.of(), expectedAnchor, 61);
            when(shortSaleService.fetchWindow(any(), eq(stock), eq(session))).thenReturn(fetch);
            when(shortSaleService.persistWindow(any(), eq(stock), eq(fetch)))
                    .thenReturn(new BackfillWindowResult(expectedAnchor, 61));

            // Act
            CoveredFillResult result = filler(status, stock).persistStep(cursor);
            int actualStepDays = (int) ChronoUnit.DAYS.between(cursor, result.filledUntil());

            // Assert — ① 스텝 폭 90일 불변, ② claimed 구간 = 반환 구간(kept==실측 61 그대로 매핑, over-claim 없음의 근거)
            assertThat(actualStepDays).isEqualTo(90);
            assertThat(result.kept()).isEqualTo(61);
        }
    }
}
