package com.aaa.collector.market.session;

import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.List;
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

    private final MarketSessionGate marketSessionGate;
    private final UsMarketSessionGate usMarketSessionGate;
    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final CoverageMetrics coverageMetrics;

    /** KRX 일봉 커버리지를 계산해 게이지에 반영한다(REQ-WM-010). */
    @Scheduled(cron = KRX_CRON, zone = "Asia/Seoul")
    public void refreshKrxCoverage() {
        LocalDate expected = marketSessionGate.computeExpectedTradeDate();
        if (expected == null) {
            log.warn("[coverage] KRX expected watermark 미산출 — 커버리지 계산 스킵");
            return;
        }
        refresh(
                WatermarkSeries.DAILY_OHLCV_KRX,
                expected,
                stockRepository.findAllActiveDomesticTradable());
    }

    /** US 일봉 커버리지를 계산해 게이지에 반영한다(REQ-WM-010). */
    @Scheduled(cron = US_CRON, zone = "Asia/Seoul")
    public void refreshUsCoverage() {
        LocalDate expected = usMarketSessionGate.computeExpectedTradeDate();
        if (expected == null) {
            log.warn("[coverage] US expected watermark 미산출 — 커버리지 계산 스킵");
            return;
        }
        refresh(
                WatermarkSeries.DAILY_OHLCV_US,
                expected,
                stockRepository.findAllActiveOverseasTradable());
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
