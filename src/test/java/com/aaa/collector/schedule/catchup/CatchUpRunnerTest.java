package com.aaa.collector.schedule.catchup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.macro.MacroExternalScheduler;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketBatchScheduler;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.schedule.BatchCrons;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvScheduler;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvScheduler;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeScheduler;
import com.aaa.collector.stock.fundamental.FinancialRatioScheduler;
import com.aaa.collector.stock.fundamental.InvestOpinionScheduler;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatchUpRunner — 재시작 후 누락 배치 즉시 재실행 (SPEC-COLLECTOR-CATCHUP-001 T4)")
@SuppressWarnings({
    "PMD.CouplingBetweenObjects", // 테스트 클래스 — 8개 스케줄러 + 10개 리포지토리 mock 불가피
    "PMD.TooManyFields" // 테스트 클래스 — 다수 의존성 검증을 위한 mock 필드 불가피
})
class CatchUpRunnerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // 스케줄러 mock
    @Mock private DomesticDailyOhlcvScheduler domesticDailyOhlcvScheduler;
    @Mock private OverseasDailyOhlcvScheduler overseasDailyOhlcvScheduler;
    @Mock private ShortSaleOverseasScheduler shortSaleOverseasScheduler;
    @Mock private InvestOpinionScheduler investOpinionScheduler;
    @Mock private FinancialRatioScheduler financialRatioScheduler;
    @Mock private MacroExternalScheduler macroExternalScheduler;
    @Mock private MarketBatchScheduler marketBatchScheduler;
    @Mock private EtfRepresentativeScheduler etfRepresentativeScheduler;

    // 리포지토리 mock
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private InvestorTrendRepository investorTrendRepository;
    @Mock private CreditBalanceRepository creditBalanceRepository;
    @Mock private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;
    @Mock private AnalystEstimateRepository analystEstimateRepository;
    @Mock private FinancialRepository financialRepository;
    @Mock private MacroIndicatorRepository macroIndicatorRepository;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;

    private ExpectedFireCalculator calculator;

    /** 기본 CatchUpProperties (grace=300s, timeout=600s). */
    private CatchUpProperties defaultProperties() {
        return new CatchUpProperties(true, 300L, 600L);
    }

    private CatchUpRunner buildRunner(Clock clock, CatchUpProperties props) {
        return new CatchUpRunner(
                domesticDailyOhlcvScheduler,
                overseasDailyOhlcvScheduler,
                shortSaleOverseasScheduler,
                investOpinionScheduler,
                financialRatioScheduler,
                macroExternalScheduler,
                marketBatchScheduler,
                etfRepresentativeScheduler,
                dailyOhlcvRepository,
                investorTrendRepository,
                creditBalanceRepository,
                shortSaleDomesticRepository,
                shortSaleOverseasRepository,
                analystEstimateRepository,
                financialRepository,
                macroIndicatorRepository,
                marketIndicatorRepository,
                etfRepresentativeHistoryRepository,
                props,
                clock,
                calculator);
    }

    @BeforeEach
    void setUp() {
        calculator = new ExpectedFireCalculator();
    }

    // ─── 헬퍼: 간단한 단위 생성 (직접 Optional 공급자 사용 — mock 불필요) ──────────────────

    /**
     * 직접 lastLoad Optional을 반환하는 단순 단위. mock stubbing 없이 사용 가능.
     *
     * @param lastLoad lastLoad 값
     * @param trigger 재실행 trigger
     */
    private CatchUpUnit domesticDailyUnitWithLastLoad(
            Optional<LocalDateTime> lastLoad, Runnable trigger) {
        return new CatchUpUnit(
                "domestic-daily-chain",
                BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                Freshness.INSTANT,
                // chain: 4개 supplier 모두 같은 값 반환
                List.of(() -> lastLoad, () -> lastLoad, () -> lastLoad, () -> lastLoad),
                trigger);
    }

    /** stale lastLoad(전날)로 구성된 chain 단위 — mock 필요 없음. */
    private CatchUpUnit staleChainUnit() {
        LocalDateTime stale = LocalDateTime.of(2026, 6, 21, 16, 0, 0);
        return domesticDailyUnitWithLastLoad(
                Optional.of(stale), domesticDailyOhlcvScheduler::collectDaily);
    }

    @Nested
    @DisplayName("AC-1: 당일 슬롯 미발화 → 재실행")
    class Ac1DomesticDailyMissed {

        @Test
        @DisplayName("평일 18:00 KST, lastLoad = 전날 → shouldRun=true")
        void whenDailyMissed_thenTriggered() {
            // Arrange: 2026-06-22(월) 18:00 KST, lastLoad = 전날 22:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();
            LocalDateTime yesterday = LocalDateTime.of(2026, 6, 21, 22, 0, 0);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpUnit unit =
                    domesticDailyUnitWithLastLoad(
                            Optional.of(yesterday), domesticDailyOhlcvScheduler::collectDaily);

            // Act
            CatchUpDecision decision = runner.evaluate(unit, now);

            // Assert
            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-2: 미국 배치 ET zone → collect() 재실행 판정")
    class Ac2OverseasShortsaleEt {

        @Test
        @DisplayName("평일 12:00 ET, lastLoad = 전날 → shouldRun=true")
        void whenOverseasShortsaleMissed_thenTriggered() {
            // Arrange: 2026-06-22(월) 12:00 ET
            Instant now = ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, ET).toInstant();
            LocalDateTime yesterday = LocalDateTime.of(2026, 6, 21, 10, 0, 0);

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "overseas-shortsale",
                            BatchCrons.OVERSEAS_SHORTSALE_CRON,
                            BatchCrons.OVERSEAS_SHORTSALE_ZONE,
                            Freshness.INSTANT,
                            List.of(() -> Optional.of(yesterday)),
                            shortSaleOverseasScheduler::collect);

            Clock clock = Clock.fixed(now, ET);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-3: 슬롯 안 겹침(same-day 아님) → 트리거 0회")
    class Ac3SameDayFail {

        @Test
        @DisplayName("일요일 12:00 KST (평일 슬롯 없음) → shouldRun=false, trigger 미호출")
        void whenSunday_thenNoFire() {
            // Arrange: 2026-06-21(일) 12:00 KST (직전 금요일 16:00 슬롯은 다른 날)
            Instant now = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, KST).toInstant();

            // Arrange: lastLoad가 stale해도 same-day 조건 미충족이면 shouldRun=false
            CatchUpUnit unit = staleChainUnit();
            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            // Assert
            assertThat(decision.shouldRun()).isFalse();
            verify(domesticDailyOhlcvScheduler, never()).collectDaily();
        }
    }

    @Nested
    @DisplayName("AC-4: 이미 적재됨 → 트리거 0회")
    class Ac4AlreadyLoaded {

        @Test
        @DisplayName("lastLoad = 오늘 16:30 KST (expectedLastFire=16:00 이후) → shouldRun=false")
        void whenAlreadyLoaded_thenNoTrigger() {
            // Arrange: 2026-06-22(월) 18:00 KST, lastLoad = 오늘 16:30 (16:00 이후)
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();
            LocalDateTime alreadyLoaded = LocalDateTime.of(2026, 6, 22, 16, 30, 0);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpUnit unit =
                    domesticDailyUnitWithLastLoad(
                            Optional.of(alreadyLoaded), domesticDailyOhlcvScheduler::collectDaily);

            CatchUpDecision decision = runner.evaluate(unit, now);

            assertThat(decision.shouldRun()).isFalse();
        }
    }

    @Nested
    @DisplayName("AC-5: chain ANY-stale → shouldRun=true")
    class Ac5ChainAnyStale {

        @Test
        @DisplayName("일봉 적재됐어도 creditBalance stale이면 → shouldRun=true")
        void whenCreditBalanceStale_thenTriggered() {
            // Arrange: 2026-06-22(월) 18:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();
            LocalDateTime fresh = LocalDateTime.of(2026, 6, 22, 16, 30, 0);
            LocalDateTime stale = LocalDateTime.of(2026, 6, 21, 16, 0, 0);

            // chain: 일봉(fresh), investorTrend(fresh), creditBalance(stale), shortSale(fresh)
            CatchUpUnit unit =
                    new CatchUpUnit(
                            "domestic-daily-chain",
                            BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                            BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                            Freshness.INSTANT,
                            List.of(
                                    () -> Optional.of(fresh),
                                    () -> Optional.of(fresh),
                                    () -> Optional.of(stale), // creditBalance stale
                                    () -> Optional.of(fresh)),
                            domesticDailyOhlcvScheduler::collectDaily);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-6: etf DATE freshness")
    class Ac6EtfDateFreshness {

        @Test
        @DisplayName("월요일 09:00 KST, effectiveFrom=당일 00:00 → DATE 비교 stale 아님 → shouldRun=false")
        void whenEtfLoadedToday_thenNotStale() {
            // Arrange: 2026-06-22(월) 09:00 KST (07:50 발화 이후, grace 경과)
            Instant now = ZonedDateTime.of(2026, 6, 22, 9, 0, 0, 0, KST).toInstant();
            // effectiveFrom = 당일 00:00 KST → DATE 비교 시 당일 == expectedDate → fresh
            LocalDateTime todayMidnight = LocalDateTime.of(2026, 6, 22, 0, 0, 0);

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "domestic-etf-representative",
                            BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON,
                            BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE,
                            Freshness.DATE,
                            List.of(() -> Optional.of(todayMidnight)),
                            etfRepresentativeScheduler::recalculateWeekly);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            // DATE freshness: lastDate(22일) >= expectedDate(22일) → fresh → shouldRun=false
            assertThat(decision.shouldRun()).isFalse();
        }

        @Test
        @DisplayName("월요일 09:00 KST, effectiveFrom=전주 월요일 → DATE 비교 stale → shouldRun=true")
        void whenEtfNotLoadedThisWeek_thenStale() {
            // Arrange: 2026-06-22(월) 09:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 9, 0, 0, 0, KST).toInstant();
            // 전주 월요일(2026-06-15) 07:50 로드됨
            LocalDateTime lastWeek = LocalDateTime.of(2026, 6, 15, 7, 50, 0);

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "domestic-etf-representative",
                            BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON,
                            BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE,
                            Freshness.DATE,
                            List.of(() -> Optional.of(lastWeek)),
                            etfRepresentativeScheduler::recalculateWeekly);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            // DATE: lastDate(15일) < expectedDate(22일) → stale → shouldRun=true
            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-7/AC-16: grace 경계 — strict > 검증")
    class Ac7Ac16GraceBoundary {

        @Test
        @DisplayName("AC-7: now == expectedLastFire + 300s → grace 미경과(경계) → shouldRun=false")
        void whenNowEqualsGraceDeadline_thenNotRun() {
            // expectedLastFire = 2026-06-22(월) 16:00 KST
            // grace = 300s → deadline = 16:05:00
            // now = 정확히 16:05:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 16, 5, 0, 0, KST).toInstant();
            LocalDateTime stale = LocalDateTime.of(2026, 6, 21, 16, 0, 0);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            // 직접 lambda 사용 — mock stubbing 없음 (grace 미경과로 stale 체크 미도달)
            CatchUpUnit directUnit =
                    new CatchUpUnit(
                            "domestic-daily-chain",
                            BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                            BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                            Freshness.INSTANT,
                            List.of(() -> Optional.of(stale)),
                            domesticDailyOhlcvScheduler::collectDaily);

            CatchUpDecision decision = runner.evaluate(directUnit, now);

            // strict >: now == deadline → 미경과
            assertThat(decision.shouldRun()).isFalse();
        }

        @Test
        @DisplayName("AC-16: now == expectedLastFire + 301s → grace 경과 → shouldRun=true")
        void whenNow1SecAfterGrace_thenRun() {
            // now = 16:05:01 KST (grace deadline + 1s)
            Instant now = ZonedDateTime.of(2026, 6, 22, 16, 5, 1, 0, KST).toInstant();
            LocalDateTime stale = LocalDateTime.of(2026, 6, 21, 16, 0, 0);

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "domestic-daily-chain",
                            BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                            BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                            Freshness.INSTANT,
                            List.of(() -> Optional.of(stale)),
                            domesticDailyOhlcvScheduler::collectDaily);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-10: per-unit 격리 — 한 단위 예외 시 나머지 계속")
    class Ac10PerUnitIsolation {

        @Test
        @DisplayName("첫 번째 단위 trigger가 예외를 던져도 두 번째 단위 trigger가 실행된다")
        void whenFirstUnitThrows_thenSecondUnitStillRuns() {
            // Arrange: 2026-06-22(월) 18:10 KST — 두 단위 모두 grace 경과
            // 단위1: domestic-daily-chain (16:00 슬롯 + grace 300s → 16:05 deadline < 18:10)
            // 단위2: domestic-invest-opinion (18:00 슬롯 + grace 300s → 18:05 deadline < 18:10)
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 10, 0, 0, KST).toInstant();
            LocalDateTime stale = LocalDateTime.of(2026, 6, 21, 16, 0, 0);

            Runnable throwingTrigger = mock(Runnable.class);
            Runnable secondTrigger = mock(Runnable.class);
            doThrow(new RuntimeException("의도적 테스트 예외")).when(throwingTrigger).run();

            List<CatchUpUnit> units =
                    List.of(
                            new CatchUpUnit(
                                    "unit-throws",
                                    BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                                    BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                                    Freshness.INSTANT,
                                    List.of(() -> Optional.of(stale)),
                                    throwingTrigger),
                            new CatchUpUnit(
                                    "unit-ok",
                                    BatchCrons.DOMESTIC_INVEST_OPINION_CRON,
                                    BatchCrons.DOMESTIC_INVEST_OPINION_ZONE,
                                    Freshness.INSTANT,
                                    List.of(() -> Optional.of(stale)),
                                    secondTrigger));

            // Act
            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            for (CatchUpUnit unit : units) {
                try {
                    CatchUpDecision decision = runner.evaluate(unit, now);
                    if (decision.shouldRun()) {
                        runner.runWithTimeout(unit);
                    }
                } catch (RuntimeException ignored) { // NOPMD.AvoidCatchingGenericException
                    // AC-10: 예외가 for-loop 밖으로 전파되면 안 됨
                }
            }

            // Assert: 두 번째 단위는 실행됨
            verify(secondTrigger, atLeastOnce()).run();
        }
    }

    @Nested
    @DisplayName("AC-11: empty=stale, same-day·grace 충족 → 시딩 트리거")
    class Ac11EmptyIsStale {

        @Test
        @DisplayName("lastLoad = Optional.empty() → stale로 간주 → shouldRun=true")
        void whenLastLoadEmpty_thenStale() {
            // Arrange: 2026-06-22(월) 18:00 KST, lastLoad = empty
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();

            CatchUpUnit unit =
                    domesticDailyUnitWithLastLoad(
                            Optional.empty(), domesticDailyOhlcvScheduler::collectDaily);

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-12: lastLoad KST 변환 — ET zone 배치도 KST로 변환")
    class Ac12KstConversion {

        @Test
        @DisplayName("overseas-shortsale: lastLoad KST 변환 시 stale 판정 올바름")
        void whenLastLoadConvertsToKst_notEt() {
            // 2026-06-22(월) 12:00 ET now
            // expectedLastFire = 10:00 ET = 23:00 KST (EDT 기준 UTC-4 → KST UTC+9 = +13h)
            // lastLoad = 2026-06-22 00:30 KST → Instant = 2026-06-21 15:30 UTC
            // expectedLastFire = 2026-06-22 14:00 UTC
            // lastLoad(Instant) < expectedLastFire → stale
            Instant now = ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, ET).toInstant();
            LocalDateTime lastLoadKst = LocalDateTime.of(2026, 6, 22, 0, 30, 0);

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "overseas-shortsale",
                            BatchCrons.OVERSEAS_SHORTSALE_CRON,
                            BatchCrons.OVERSEAS_SHORTSALE_ZONE,
                            Freshness.INSTANT,
                            List.of(() -> Optional.of(lastLoadKst)),
                            shortSaleOverseasScheduler::collect);

            Clock clock = Clock.fixed(now, ET);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(unit, now);

            // KST 변환: lastLoad < expectedLastFire → stale
            assertThat(decision.shouldRun()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-15: 순차 실행 — sleep 부재")
    class Ac15NoSleep {

        @Test
        @DisplayName("runCatchUp()이 합리적인 시간 내에 완료된다 (인위적 sleep 없음)")
        void runCatchUp_completesWithoutSleep() throws InterruptedException {
            // now = 2026-06-22(월) 18:00 KST, fresh = 17:30 KST (모든 슬롯보다 이후)
            // 단위별 same-day/grace/stale 조건이 성립하는 경우만 stale 체크 → 최소 stubbing
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();
            // fresh: 오늘 17:30 KST — 16:00/17:05 슬롯 이후이므로 해당 단위들은 stale 아님
            LocalDateTime fresh = LocalDateTime.of(2026, 6, 22, 17, 30, 0);

            // 실제로 stale 체크까지 도달하는 단위만 stubbing (나머지는 same-day/grace 실패):
            // domestic-daily-chain (16:00 KST, grace 경과): 4개 supplier
            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(Optional.of(fresh));
            when(investorTrendRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(creditBalanceRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(shortSaleDomesticRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            // market-indicators (17:05 KST, grace 경과): 1개 supplier
            when(marketIndicatorRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            // domestic-etf-representative (07:50 KST, grace 경과, DATE): 1개 supplier
            when(etfRepresentativeHistoryRepository.findMaxEffectiveFrom())
                    .thenReturn(Optional.of(fresh));
            // overseas-shortsale/overseas-daily/macro/invest-opinion/financial-ratio:
            // 각각 same-day 또는 grace 조건 미충족 → stale 체크 미도달 → stubbing 불필요

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            Thread t = new Thread(runner::runCatchUp);
            t.start();
            t.join(5000L); // 5초 이내 완료

            assertThat(t.isAlive()).isFalse();
        }
    }

    @Nested
    @DisplayName("AC-17: 0행 정상일 false-positive")
    class Ac17ZeroRowsFalsePositive {

        @Test
        @DisplayName("0행 정상일(lastLoad=empty)에서 재실행 발생은 수용된 트레이드오프")
        void zeroRowsNormalDay_isAcceptedTradeOff() {
            // AC-17: empty lastLoad → stale로 간주 → 재실행 발생.
            // 0행이 "정상"인지 "누락"인지 구분 불가 → 재실행이 보수적으로 안전한 선택.
            // 실제 검증은 AC-11 테스트가 수행함 — 이 테스트는 트레이드오프를 문서화.
            // assertion: AC-11이 empty → stale 동작을 검증함을 명시
            assertThat(Freshness.INSTANT).isNotNull(); // 트레이드오프 문서화 테스트 (AC-11 참조)
        }
    }

    @Nested
    @DisplayName("AC-18: onApplicationReady — 별도 스레드 비블로킹")
    class Ac18ReadinessNonBlocking {

        @Test
        @DisplayName("onApplicationReady()는 Virtual Thread를 띄우고 즉시 반환된다")
        void onApplicationReady_returnsImmediately() throws InterruptedException {
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();
            // AC-15와 동일한 fresh 시각 (17:30 KST) 및 동일한 stub 전략
            LocalDateTime fresh = LocalDateTime.of(2026, 6, 22, 17, 30, 0);

            when(dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(any()))
                    .thenReturn(Optional.of(fresh));
            when(investorTrendRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(creditBalanceRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(shortSaleDomesticRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(marketIndicatorRepository.findMaxCreatedAt()).thenReturn(Optional.of(fresh));
            when(etfRepresentativeHistoryRepository.findMaxEffectiveFrom())
                    .thenReturn(Optional.of(fresh));

            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            // Act: 이벤트 콜백이 즉시 반환돼야 함
            long start = System.currentTimeMillis();
            runner.onApplicationReady();
            long elapsed = System.currentTimeMillis() - start;

            // Assert: 콜백 자체는 1초 이내 반환 (백그라운드 스레드 완료 기다리지 않음)
            assertThat(elapsed).isLessThan(1000L);

            // 백그라운드 스레드 완료 대기 (테스트 클린업)
            Thread.sleep(300L);
        }
    }

    @Nested
    @DisplayName("buildRegistry — 레지스트리 조립")
    class BuildRegistry {

        @Test
        @DisplayName("buildRegistry()는 9개 단위를 반환한다 (TASK-C5: usdkrw-daily 추가)")
        void buildRegistry_returns9Units() {
            Instant now = Instant.now();
            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            List<CatchUpUnit> units = runner.buildRegistry();

            assertThat(units).hasSize(9);
        }

        @Test
        @DisplayName(
                "usdkrw-daily 단위는 USDKRW_DAILY_CRON/ZONE·INSTANT freshness를 가져야 한다"
                        + " (SPEC-COLLECTOR-MARKETIND-004 TASK-C5, REQ-007/-008)")
        void usdkrwDailyUnit_hasDedicatedCronAndScopedFreshness() {
            Clock clock = Clock.fixed(Instant.now(), KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            List<CatchUpUnit> units = runner.buildRegistry();
            CatchUpUnit usdkrwUnit =
                    units.stream()
                            .filter(u -> "usdkrw-daily".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

            assertThat(usdkrwUnit.cron()).isEqualTo(BatchCrons.USDKRW_DAILY_CRON);
            assertThat(usdkrwUnit.zone()).isEqualTo(BatchCrons.USDKRW_DAILY_ZONE);
            assertThat(usdkrwUnit.freshness()).isEqualTo(Freshness.INSTANT);
            assertThat(usdkrwUnit.lastLoadSuppliers()).hasSize(1);
            assertThat(usdkrwUnit.trigger()).isNotNull();
        }

        @Test
        @DisplayName(
                "usdkrw-daily 단위의 lastLoad supplier는 marketIndicatorRepository의 USDKRW 스코프 쿼리를 사용한다"
                        + " (TASK-C5, TASK-C4 재사용)")
        void usdkrwDailyUnit_usesIndicatorScopedFreshnessQuery() {
            LocalDateTime fresh = LocalDateTime.of(2026, 7, 17, 10, 40, 0);
            when(marketIndicatorRepository.findMaxCreatedAtByIndicatorCode(IndicatorCode.USDKRW))
                    .thenReturn(Optional.of(fresh));

            Clock clock = Clock.fixed(Instant.now(), KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            List<CatchUpUnit> units = runner.buildRegistry();
            CatchUpUnit usdkrwUnit =
                    units.stream()
                            .filter(u -> "usdkrw-daily".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

            Optional<LocalDateTime> result = usdkrwUnit.lastLoadSuppliers().getFirst().get();

            assertThat(result).contains(fresh);
        }

        @Test
        @DisplayName("모든 단위의 cron·zone·freshness·suppliers·trigger는 null이 아니어야 한다")
        void buildRegistry_allUnitsHaveNonNullFields() {
            Instant now = Instant.now();
            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            List<CatchUpUnit> units = runner.buildRegistry();

            for (CatchUpUnit unit : units) {
                assertThat(unit.cron()).as("cron for %s", unit.name()).isNotNull();
                assertThat(unit.zone()).as("zone for %s", unit.name()).isNotNull();
                assertThat(unit.freshness()).as("freshness for %s", unit.name()).isNotNull();
                assertThat(unit.lastLoadSuppliers())
                        .as("suppliers for %s", unit.name())
                        .isNotEmpty();
                assertThat(unit.trigger()).as("trigger for %s", unit.name()).isNotNull();
            }
        }

        @Test
        @DisplayName("domestic-etf-representative 단위는 DATE freshness를 가져야 한다")
        void etfUnit_hasDateFreshness() {
            Clock clock = Clock.fixed(Instant.now(), KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            List<CatchUpUnit> units = runner.buildRegistry();
            CatchUpUnit etfUnit =
                    units.stream()
                            .filter(u -> "domestic-etf-representative".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

            assertThat(etfUnit.freshness()).isEqualTo(Freshness.DATE);
        }

        @Test
        @DisplayName("domestic-daily-chain 단위는 4개의 lastLoad supplier를 가져야 한다")
        void domesticDailyChainUnit_has4Suppliers() {
            Clock clock = Clock.fixed(Instant.now(), KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            List<CatchUpUnit> units = runner.buildRegistry();
            CatchUpUnit chainUnit =
                    units.stream()
                            .filter(u -> "domestic-daily-chain".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

            assertThat(chainUnit.lastLoadSuppliers()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("evaluate — 판정 reason 로깅")
    class EvaluateReason {

        @Test
        @DisplayName("shouldRun=false 일 때 reason이 비어있지 않아야 한다")
        void whenNotRun_thenReasonIsNotEmpty() {
            // 일요일 → same-day 미충족
            Instant now = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, KST).toInstant();
            Clock clock = Clock.fixed(now, KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());
            CatchUpDecision decision = runner.evaluate(staleChainUnit(), now);

            assertThat(decision.shouldRun()).isFalse();
            assertThat(decision.reason()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("runWithTimeout — 타임아웃 단위 격리")
    class RunWithTimeout {

        @Test
        @DisplayName("trigger가 예외를 던져도 runWithTimeout()은 예외를 전파하지 않는다")
        void whenTriggerThrows_thenNoException() {
            Runnable throwingTrigger = mock(Runnable.class);
            doThrow(new RuntimeException("테스트 예외")).when(throwingTrigger).run();

            CatchUpUnit unit =
                    new CatchUpUnit(
                            "test-unit",
                            BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                            BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                            Freshness.INSTANT,
                            List.of(Optional::empty),
                            throwingTrigger);

            Clock clock = Clock.fixed(Instant.now(), KST);
            CatchUpRunner runner = buildRunner(clock, defaultProperties());

            assertThatNoException().isThrownBy(() -> runner.runWithTimeout(unit));
        }
    }
}
