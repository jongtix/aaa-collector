package com.aaa.collector.market.session;

import com.aaa.collector.backfill.BackfillDensityCalculator;
import com.aaa.collector.backfill.BackfillDensityCalculator.StockDensityInput;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.observability.BackfillDensityMetrics;
import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일봉 데이터 커버리지 일1회(deadline 직후) 계산 (SPEC-OBSV-WATERMARK-001 REQ-WM-010/011).
 *
 * <p>daily-ohlcv-krx/us에 한해 활성 유니버스(§3 실측: KRX 159, US 75 — {@link
 * StockRepository#findAllActiveDomesticTradable()}/{@link
 * StockRepository#findAllActiveOverseasTradable()}가 이미 지수·비대상 시장을 제외한 STOCK+ETF 종목만 반환) 대비
 * expected_watermark 날짜 행 보유 종목 비율을 계산한다. 밀도가 검증되지 않은 다른 시리즈는 커버리지 룰을 만들지 않는다(CR-02).
 *
 * <p>패키지 위치: {@code market.session} — {@link MarketSessionGate}/{@link UsMarketSessionGate}의 {@code
 * computeExpectedTradeDate()}에 의존하므로 {@code observability} 패키지에 두면 {@code kis → observability →
 * market → kis} 순환 의존이 발생한다(MdcArchitectureTest). market 슬라이스는 이미 kis에 의존하므로 이 클래스는 market →
 * observability 방향으로만 의존한다.
 */
// @MX:NOTE: [AUTO] 커버리지 게이지 일1회 계산 진입점
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-010/011
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageRefresher {

    /** KRX 커버리지 계산 cron — 15:30 KST 마감 + 4h deadline(19:30) 직후. */
    static final String KRX_CRON = "0 35 19 * * *";

    /** US 커버리지 계산 cron — 16:00 ET 마감 + 4h deadline(20:00 ET) 이후 KST 아침. */
    static final String US_CRON = "0 0 10 * * *";

    /**
     * 미국 KIS 일봉 데이터 고정 하한 벽 (SPEC-COLLECTOR-BACKFILL-010 §1.3). 미국은 {@code max(listed_date, 이 벽)}이
     * 항상 신뢰 가능한 하한이다(REQ-150/-154) — {@code listed_date} NULL 또는 벽 이하면 벽 자체, 벽보다 위면 listed_date가
     * floor이나 그 경우도 벽보다 상위이므로 여전히 "값이 존재"한다(신뢰도 판별은 게이트 로직 전용, 게이지 A는 값 존재만으로 충분).
     */
    private static final LocalDate US_DATA_WALL = LocalDate.of(2007, 8, 20);

    private static final String DAILY_OHLCV = "daily_ohlcv";
    private static final String TARGET_TYPE_STOCK = "STOCK";

    private final MarketSessionGate marketSessionGate;
    private final UsMarketSessionGate usMarketSessionGate;
    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final CoverageMetrics coverageMetrics;
    private final BackfillStatusRepository backfillStatusRepository;
    private final BackfillDensityMetrics densityMetrics;

    /** KRX 일봉 커버리지·밀도 게이지를 계산해 반영한다(REQ-WM-010, REQ-BACKFILL-154/-155). */
    @Scheduled(cron = KRX_CRON, zone = "Asia/Seoul")
    public void refreshKrxCoverage() {
        List<Stock> universe = stockRepository.findAllActiveDomesticTradable();
        refreshDensity(WatermarkSeries.DAILY_OHLCV_KRX, universe, false);

        LocalDate expected = marketSessionGate.computeExpectedTradeDate();
        if (expected == null) {
            log.warn("[coverage] KRX expected watermark 미산출 — 커버리지 계산 스킵");
            return;
        }
        refresh(WatermarkSeries.DAILY_OHLCV_KRX, expected, universe);
    }

    /** US 일봉 커버리지·밀도 게이지를 계산해 반영한다(REQ-WM-010, REQ-BACKFILL-154/-155). */
    @Scheduled(cron = US_CRON, zone = "Asia/Seoul")
    public void refreshUsCoverage() {
        List<Stock> universe = stockRepository.findAllActiveOverseasTradable();
        refreshDensity(WatermarkSeries.DAILY_OHLCV_US, universe, true);

        LocalDate expected = usMarketSessionGate.computeExpectedTradeDate();
        if (expected == null) {
            log.warn("[coverage] US expected watermark 미산출 — 커버리지 계산 스킵");
            return;
        }
        refresh(WatermarkSeries.DAILY_OHLCV_US, expected, universe);
    }

    /**
     * 과거 데이터 밀도 게이지 A(하한 미달)·B(내부 구멍)를 계산한다 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155,
     * §4.3).
     *
     * <p>게이지 A는 신뢰 가능한 하한이 있는 종목만 모집단에 포함한다 — 미국은 항상(벽 상수), 국내는 검증 기준선(REQ-152)이 있는 종목만(DP-1 미확정 시
     * 분기 B 기본, MA-01 레거시 무신호 오탐 차단). 게이지 B는 신뢰 하한이 불필요하며 레거시 포함 전 종목을 감시한다.
     */
    // @MX:NOTE: [AUTO] 밀도 게이지 A(하한 미달)·B(내부 구멍) 계산 진입점 — 기대 최과거 = max(listed_date, ...)
    // @MX:REASON: SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-010
    private void refreshDensity(WatermarkSeries series, List<Stock> universe, boolean overseas) {
        if (universe.isEmpty()) {
            densityMetrics.setBelowFloorCount(series, 0);
            densityMetrics.setInternalGapCount(series, 0);
            return;
        }
        Set<Long> stockIds = universe.stream().map(Stock::getId).collect(Collectors.toSet());
        Map<Long, Object[]> minMaxCountByStockId = new HashMap<>();
        for (Object[] row : dailyOhlcvRepository.findMinMaxCountByStockIds(stockIds)) {
            minMaxCountByStockId.put((Long) row[0], row);
        }
        List<LocalDate> calendar = dailyOhlcvRepository.findDistinctTradeDatesByStockIds(stockIds);

        List<StockDensityInput> inputs =
                universe.stream()
                        .map(stock -> toDensityInput(stock, minMaxCountByStockId, overseas))
                        .filter(input -> input.minTradeDate() != null)
                        .toList();

        long belowFloor = BackfillDensityCalculator.countBelowFloor(inputs);
        long internalGap = BackfillDensityCalculator.countInternalGaps(inputs, calendar);
        densityMetrics.setBelowFloorCount(series, belowFloor);
        densityMetrics.setInternalGapCount(series, internalGap);
        log.info(
                "[density] series={}, belowFloor={}, internalGap={}, universe={}",
                series.seriesLabel(),
                belowFloor,
                internalGap,
                universe.size());
    }

    private StockDensityInput toDensityInput(
            Stock stock, Map<Long, Object[]> minMaxCountByStockId, boolean overseas) {
        Object[] row = minMaxCountByStockId.get(stock.getId());
        if (row == null) {
            return new StockDensityInput(null, null, 0, null);
        }
        LocalDate min = (LocalDate) row[1];
        LocalDate max = (LocalDate) row[2];
        int count = ((Number) row[3]).intValue();
        LocalDate trustedFloor = resolveTrustedFloor(stock, overseas);
        return new StockDensityInput(min, max, count, trustedFloor);
    }

    /**
     * 신뢰 가능한 기대 최과거일(trusted floor)을 산정한다.
     *
     * <p>미국: {@code max(listed_date, 2007-08-20 벽)} — 벽 자체가 상시 신뢰 하한이므로 항상 값이 존재한다(REQ-154). 국내: 검증
     * 기준선({@code verified_at IS NOT NULL} 완료의 {@code last_collected_date})만 신뢰 가능 — 없으면 {@code
     * null}(모집단 제외, MA-01 레거시 무신호 오탐 차단, §7 DP-1 분기 B 기본).
     */
    private LocalDate resolveTrustedFloor(Stock stock, boolean overseas) {
        if (overseas) {
            LocalDate listed = stock.getListedDate();
            return listed == null || listed.isBefore(US_DATA_WALL) ? US_DATA_WALL : listed;
        }
        return backfillStatusRepository
                .findVerifiedBaseline(TARGET_TYPE_STOCK, stock.getSymbol(), DAILY_OHLCV)
                .orElse(null);
    }

    private void refresh(WatermarkSeries series, LocalDate expected, List<Stock> universe) {
        if (universe.isEmpty()) {
            coverageMetrics.setRatio(series, 0.0);
            return;
        }
        Set<Long> stockIds = universe.stream().map(Stock::getId).collect(Collectors.toSet());
        long covered =
                dailyOhlcvRepository.countDistinctStockIdsByTradeDateAndStockIdIn(
                        expected, stockIds);
        double ratio = (double) covered / universe.size();
        coverageMetrics.setRatio(series, ratio);
        log.info(
                "[coverage] series={}, expected={}, covered={}, universe={}, ratio={}",
                series.seriesLabel(),
                expected,
                covered,
                universe.size(),
                ratio);
    }
}
