package com.aaa.collector.warmstart;

import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.macro.MacroIndicatorRepository;
import com.aaa.collector.macro.enums.MacroSource;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.news.DomesticNewsHeadlineRepository;
import com.aaa.collector.news.overseas.OverseasNewsHeadlineRepository;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.CreditBalanceRepository;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.InvestorTrendRepository;
import com.aaa.collector.stock.ShortSaleDomesticRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.exthours.ExtendedHoursRepository;
import com.aaa.collector.stock.exthours.Session;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 DB {@code MAX(기준 날짜 컬럼)}로 {@code aaa_collector_data_watermark_seconds} 게이지를 초기화한다
 * (SPEC-OBSV-WATERMARK-001 REQ-WM-003).
 *
 * <p>§3 사전 17 시계열 각각을 조회하여 {@link WatermarkMetrics#resync(WatermarkSeries, LocalDate)}로 절대값 설정한다.
 * 비차단(non-blocking) — 한 시리즈 조회 실패 시 warn 로깅 후 나머지를 계속 처리하며, {@link WatermarkMetrics#initGauges()}가
 * 이미 0으로 사전 등록했으므로 실패한 시리즈도 absent가 되지 않는다(부분 소실 방어).
 */
// @MX:ANCHOR: [AUTO] 부팅 시 WatermarkMetrics 게이지 warm-start 진입점
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-003 — 17개 리포지토리에서 fan_in >= 3, vmalert 데드맨 룰 무력화 방지
// @MX:SPEC: SPEC-OBSV-WATERMARK-001
@Slf4j
@Component
@RequiredArgsConstructor
public class WatermarkWarmStarter implements ApplicationRunner {

    private static final List<Market> DOMESTIC_MARKETS =
            List.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    private static final List<Market> OVERSEAS_MARKETS =
            List.of(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);

    private final WatermarkMetrics watermarkMetrics;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final InvestorTrendRepository investorTrendRepository;
    private final CreditBalanceRepository creditBalanceRepository;
    private final ShortSaleDomesticRepository shortSaleDomesticRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final MacroIndicatorRepository macroIndicatorRepository;
    private final AnalystEstimateRepository analystEstimateRepository;
    private final DisclosureRepository disclosureRepository;
    private final DomesticNewsHeadlineRepository domesticNewsHeadlineRepository;
    private final OverseasNewsHeadlineRepository overseasNewsHeadlineRepository;
    private final ExtendedHoursRepository extendedHoursRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Watermark warm-start 시작 (SPEC-OBSV-WATERMARK-001 REQ-WM-003)");

        warm(
                WatermarkSeries.DAILY_OHLCV_KRX,
                () -> dailyOhlcvRepository.findMaxTradeDateByMarketsIn(DOMESTIC_MARKETS));
        warm(
                WatermarkSeries.DAILY_OHLCV_US,
                () -> dailyOhlcvRepository.findMaxTradeDateByMarketsIn(OVERSEAS_MARKETS));
        warm(WatermarkSeries.INVESTOR_TREND, investorTrendRepository::findMaxTradeDate);
        warm(WatermarkSeries.CREDIT_BALANCE, creditBalanceRepository::findMaxTradeDate);
        warm(WatermarkSeries.SHORT_SALE_DOMESTIC, shortSaleDomesticRepository::findMaxTradeDate);
        warm(
                WatermarkSeries.SHORT_SALE_OVERSEAS_DAILY,
                shortSaleOverseasRepository::findMaxDailyTradeDate);
        warm(
                WatermarkSeries.SHORT_SALE_OVERSEAS_INTEREST,
                shortSaleOverseasRepository::findMaxShortInterestDate);
        warm(
                WatermarkSeries.MARKET_USDKRW,
                () ->
                        marketIndicatorRepository.findMaxTradeDateByIndicatorCode(
                                IndicatorCode.USDKRW));
        warm(
                WatermarkSeries.MARKET_VIX,
                () -> marketIndicatorRepository.findMaxTradeDateByIndicatorCode(IndicatorCode.VIX));
        warm(
                WatermarkSeries.MACRO_ECOS,
                () -> macroIndicatorRepository.findMaxTradeDateBySource(MacroSource.ECOS));
        warm(
                WatermarkSeries.MACRO_FRED,
                () -> macroIndicatorRepository.findMaxTradeDateBySource(MacroSource.FRED));
        warm(WatermarkSeries.ANALYST_ESTIMATES, analystEstimateRepository::findMaxTradeDate);
        warm(WatermarkSeries.DISCLOSURES, disclosureRepository::findMaxRceptDt);
        warmDateTime(
                WatermarkSeries.NEWS_DOMESTIC, domesticNewsHeadlineRepository::findMaxPublishedAt);
        warmDateTime(
                WatermarkSeries.NEWS_OVERSEAS, overseasNewsHeadlineRepository::findMaxPublishedAt);
        warm(
                WatermarkSeries.EXTENDED_HOURS_PRE,
                () -> extendedHoursRepository.findMaxTradeDateBySession(Session.PRE));
        warm(
                WatermarkSeries.EXTENDED_HOURS_AFTER,
                () -> extendedHoursRepository.findMaxTradeDateBySession(Session.AFTER));

        log.info("Watermark warm-start 완료");
    }

    private void warm(WatermarkSeries series, DateQuery query) {
        try {
            Optional<LocalDate> result = query.findMax();
            watermarkMetrics.resync(series, result.orElse(null));
            log.info(
                    "Watermark warm-start 완료 — series={}, date={}",
                    series.seriesLabel(),
                    result.orElse(null));
        } catch (DataAccessException e) {
            log.warn(
                    "Watermark warm-start 실패 — series={}, 0으로 사전 등록 유지. error={}",
                    series.seriesLabel(),
                    e.getMessage());
        }
    }

    private void warmDateTime(WatermarkSeries series, DateTimeQuery query) {
        try {
            Optional<LocalDateTime> result = query.findMax();
            watermarkMetrics.resync(series, result.map(LocalDateTime::toLocalDate).orElse(null));
            log.info(
                    "Watermark warm-start 완료 — series={}, date={}",
                    series.seriesLabel(),
                    result.orElse(null));
        } catch (DataAccessException e) {
            log.warn(
                    "Watermark warm-start 실패 — series={}, 0으로 사전 등록 유지. error={}",
                    series.seriesLabel(),
                    e.getMessage());
        }
    }

    @FunctionalInterface
    private interface DateQuery {
        Optional<LocalDate> findMax();
    }

    @FunctionalInterface
    private interface DateTimeQuery {
        Optional<LocalDateTime> findMax();
    }
}
