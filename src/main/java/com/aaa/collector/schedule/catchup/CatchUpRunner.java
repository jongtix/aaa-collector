package com.aaa.collector.schedule.catchup;

import com.aaa.collector.macro.MacroExternalScheduler;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.market.MarketBatchScheduler;
import com.aaa.collector.market.MarketIndicatorRepository;
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
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfRepresentativeHistoryRepository;
import com.aaa.collector.stock.etf.EtfRepresentativeScheduler;
import com.aaa.collector.stock.fundamental.FinancialRatioScheduler;
import com.aaa.collector.stock.fundamental.InvestOpinionScheduler;
import com.aaa.collector.stock.shortsale.overseas.ShortSaleOverseasScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 재시작 후 누락 배치를 즉시 재실행한다 (SPEC-COLLECTOR-CATCHUP-001 T4).
 *
 * <p>부팅 완료({@link ApplicationReadyEvent}) 후 Virtual Thread에서 8개 배치 단위를 순차 평가한다. readiness를 비블로킹으로
 * 유지하기 위해 별도 스레드에서 실행한다(AC-15, AC-18).
 *
 * <p>판정 3단계:
 *
 * <ol>
 *   <li>same-day: expectedLastFire가 now와 같은 날(zone 기준)인지 확인
 *   <li>grace: now &gt; expectedLastFire + graceSeconds (strict &gt;, 경계 == 는 보류)
 *   <li>stale: lastLoad &lt; expectedLastFire (INSTANT) 또는 날짜 비교 (DATE). chain은 ANY-stale
 * </ol>
 *
 * <p>단위별 try-catch로 격리하여 한 단위 예외가 전체를 막지 않는다(AC-10).
 */
// @MX:ANCHOR: [AUTO] 재시작 후 누락 배치 재실행 진입점
// @MX:REASON: SPEC-COLLECTOR-CATCHUP-001 — ApplicationReadyEvent 리스너; 8개 스케줄러 fan_in >= 3
@Slf4j
@Component
@EnableConfigurationProperties(CatchUpProperties.class)
@ConditionalOnProperty(name = "aaa.catchup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CatchUpRunner {

    /** lastLoad 변환 기준 zone — unit.zone과 무관하게 항상 KST 고정 (MA-02, AC-12). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final List<Market> DOMESTIC_MARKETS =
            List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    private static final List<Market> OVERSEAS_MARKETS =
            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);

    private final DomesticDailyOhlcvScheduler domesticDailyOhlcvScheduler;
    private final OverseasDailyOhlcvScheduler overseasDailyOhlcvScheduler;
    private final ShortSaleOverseasScheduler shortSaleOverseasScheduler;
    private final InvestOpinionScheduler investOpinionScheduler;
    private final FinancialRatioScheduler financialRatioScheduler;
    private final MacroExternalScheduler macroExternalScheduler;
    private final MarketBatchScheduler marketBatchScheduler;
    private final EtfRepresentativeScheduler etfRepresentativeScheduler;

    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final InvestorTrendRepository investorTrendRepository;
    private final CreditBalanceRepository creditBalanceRepository;
    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final AnalystEstimateRepository analystEstimateRepository;
    private final FinancialRepository financialRepository;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final EtfRepresentativeHistoryRepository etfRepresentativeHistoryRepository;

    private final CatchUpProperties properties;
    private final Clock clock;
    private final ExpectedFireCalculator calculator;

    /**
     * 부팅 완료 이벤트 수신 후 별도 Virtual Thread에서 catch-up을 실행한다.
     *
     * <p>이벤트 콜백이 즉시 반환되어 readiness 비블로킹을 보장한다(AC-18).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("catch-up-runner").start(this::runCatchUp);
    }

    /** catch-up 실행 — 8개 단위를 순차 평가하고 stale 단위를 재실행한다. */
    void runCatchUp() {
        log.info("[catch-up] 누락 배치 점검 시작");
        List<CatchUpUnit> units = buildRegistry();
        Instant now = clock.instant();

        for (CatchUpUnit unit : units) {
            try {
                CatchUpDecision decision = evaluate(unit, now);
                if (decision.shouldRun()) {
                    log.info("[catch-up] batch={} 누락 감지 → 즉시 재실행", unit.name());
                    runWithTimeout(unit);
                } else {
                    log.debug("[catch-up] batch={} 재실행 불필요 — {}", unit.name(), decision.reason());
                }
            } catch (Exception e) {
                log.warn("[catch-up] batch={} 처리 중 예외 — 다음 단위로 계속", unit.name(), e);
            }
        }
        log.info("[catch-up] 누락 배치 점검 완료");
    }

    /**
     * 단위 판정 로직 (3단계: same-day / grace / stale).
     *
     * @param unit 평가 대상 단위
     * @param now 현재 시각
     * @return 판정 결과
     */
    CatchUpDecision evaluate(CatchUpUnit unit, Instant now) {
        // 1. expectedLastFire 계산
        Optional<Instant> maybeExpected = calculator.calculate(unit.cron(), unit.zone(), now);
        if (maybeExpected.isEmpty()) {
            return new CatchUpDecision(unit.name(), false, "슬롯 없음(8일 lookback 내 발화 없음)");
        }
        Instant expectedLastFire = maybeExpected.get();

        // 2. same-day 체크: expectedLastFire의 zone 기준 날짜 == now의 zone 기준 날짜
        ZoneId zone = ZoneId.of(unit.zone());
        LocalDate expectedDate = expectedLastFire.atZone(zone).toLocalDate();
        LocalDate nowDate = now.atZone(zone).toLocalDate();
        if (!expectedDate.equals(nowDate)) {
            return new CatchUpDecision(unit.name(), false, "same-day 조건 미충족(다른 날짜 슬롯)");
        }

        // 3. grace 체크: now > expectedLastFire + graceSeconds (strict >)
        Instant graceDeadline = expectedLastFire.plusSeconds(properties.graceSeconds());
        if (!now.isAfter(graceDeadline)) {
            return new CatchUpDecision(unit.name(), false, "grace 유예 중(아직 미경과)");
        }

        // 4. stale 체크
        boolean stale = isStale(unit, expectedLastFire);
        if (!stale) {
            return new CatchUpDecision(unit.name(), false, "이미 적재됨(stale 아님)");
        }

        return new CatchUpDecision(unit.name(), true, "누락 감지");
    }

    /**
     * lastLoad가 expectedLastFire보다 오래됐는지(stale) 판정한다.
     *
     * <p>chain 단위(domestic-daily-chain)는 suppliers 중 하나라도 stale이면 단위 stale. {@link
     * Optional#empty()} → stale (신규 테이블 시딩). lastLoad 변환은 항상 KST 고정(MA-02, AC-12).
     */
    private boolean isStale(CatchUpUnit unit, Instant expectedLastFire) {
        for (var supplier : unit.lastLoadSuppliers()) {
            Optional<LocalDateTime> lastLoadOpt = supplier.get();
            if (lastLoadOpt.isEmpty()) {
                return true; // 신규 테이블 → stale
            }
            // lastLoad LocalDateTime → Instant (항상 KST 변환, MA-02)
            Instant lastLoadInstant = lastLoadOpt.get().atZone(KST).toInstant();

            if (unit.freshness() == Freshness.DATE) {
                // DATE 비교: 날짜만 비교 (KST 기준)
                LocalDate lastLoadDate = lastLoadInstant.atZone(KST).toLocalDate();
                LocalDate expectedDate = expectedLastFire.atZone(KST).toLocalDate();
                if (lastLoadDate.isBefore(expectedDate)) {
                    return true;
                }
            } else {
                // INSTANT 비교: lastLoad < expectedLastFire
                if (lastLoadInstant.isBefore(expectedLastFire)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 단위 배치를 타임아웃 내에 실행한다.
     *
     * <p>타임아웃 초과 시 인터럽트(베스트-에포트)하고 다음 단위로 진행한다(AC-18). package-private: 같은 패키지의 테스트에서 접근 가능.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // ExecutionException 원인 로깅
    void runWithTimeout(CatchUpUnit unit) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> unit.trigger().run());
        try {
            future.get(properties.unitTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[catch-up] batch={} 타임아웃 — 다음 단위로 진행", unit.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[catch-up] batch={} 인터럽트", unit.name());
        } catch (ExecutionException e) {
            log.error("[catch-up] batch={} 재실행 중 예외", unit.name(), e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 8개 catch-up 단위 레지스트리를 조립한다.
     *
     * <p>domestic-daily-chain: 4개 lastLoad supplier — ANY-stale이면 단위 stale(AC-5).
     */
    List<CatchUpUnit> buildRegistry() {
        return List.of(
                new CatchUpUnit(
                        "domestic-daily-chain",
                        BatchCrons.DOMESTIC_DAILY_CHAIN_CRON,
                        BatchCrons.DOMESTIC_DAILY_CHAIN_ZONE,
                        Freshness.INSTANT,
                        List.of(
                                () ->
                                        dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(
                                                DOMESTIC_MARKETS),
                                investorTrendRepository::findMaxCreatedAt,
                                creditBalanceRepository::findMaxCreatedAt,
                                shortSaleDomesticRepository::findMaxCreatedAt),
                        domesticDailyOhlcvScheduler::collectDaily),
                new CatchUpUnit(
                        "overseas-daily",
                        BatchCrons.OVERSEAS_DAILY_CRON,
                        BatchCrons.OVERSEAS_DAILY_ZONE,
                        Freshness.INSTANT,
                        List.of(
                                () ->
                                        dailyOhlcvRepository.findMaxCreatedAtByMarketsIn(
                                                OVERSEAS_MARKETS)),
                        overseasDailyOhlcvScheduler::collectDaily),
                new CatchUpUnit(
                        "overseas-shortsale",
                        BatchCrons.OVERSEAS_SHORTSALE_CRON,
                        BatchCrons.OVERSEAS_SHORTSALE_ZONE,
                        Freshness.INSTANT,
                        List.of(shortSaleOverseasRepository::findMaxDailyCollectedAt),
                        shortSaleOverseasScheduler::collect),
                new CatchUpUnit(
                        "domestic-invest-opinion",
                        BatchCrons.DOMESTIC_INVEST_OPINION_CRON,
                        BatchCrons.DOMESTIC_INVEST_OPINION_ZONE,
                        Freshness.INSTANT,
                        List.of(analystEstimateRepository::findMaxCreatedAt),
                        investOpinionScheduler::collectInvestOpinion),
                new CatchUpUnit(
                        "domestic-financial-ratio",
                        BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON,
                        BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE,
                        Freshness.INSTANT,
                        List.of(financialRepository::findMaxCreatedAt),
                        financialRatioScheduler::collectFinancialRatio),
                new CatchUpUnit(
                        "macro-external",
                        BatchCrons.MACRO_EXTERNAL_CRON,
                        BatchCrons.MACRO_EXTERNAL_ZONE,
                        Freshness.INSTANT,
                        List.of(macroIndicatorRepository::findMaxCreatedAt),
                        macroExternalScheduler::collectExternal),
                new CatchUpUnit(
                        "market-indicators",
                        BatchCrons.MARKET_INDICATORS_CRON,
                        BatchCrons.MARKET_INDICATORS_ZONE,
                        Freshness.INSTANT,
                        List.of(marketIndicatorRepository::findMaxCreatedAt),
                        marketBatchScheduler::collectMarket),
                new CatchUpUnit(
                        "domestic-etf-representative",
                        BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_CRON,
                        BatchCrons.DOMESTIC_ETF_REPRESENTATIVE_ZONE,
                        Freshness.DATE,
                        List.of(etfRepresentativeHistoryRepository::findMaxEffectiveFrom),
                        etfRepresentativeScheduler::recalculateWeekly));
    }
}
