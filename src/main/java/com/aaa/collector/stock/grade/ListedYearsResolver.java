package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.enums.AssetType;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 종목의 경과 상장 연수(listedYears)를 계산한다 (REQ-STOCKMETA-013, SPEC-COLLECTOR-STOCKMETA-001).
 *
 * <p>listedDate가 NULL인 경우 OHLCV MIN(trade_date)로 근사한다 (M3 1안):
 *
 * <ul>
 *   <li>OHLCV 없음 → 보수적 신규로 간주 (A 미수여)
 *   <li>OHLCV MIN &ge; 7년 → 장기 상장 확인 fallback (A 등급 후보)
 *   <li>OHLCV MIN &lt; 7년 → 신규로 간주 (A 미수여)
 * </ul>
 *
 * <p>INDEX/COMMODITY 자산유형은 OHLCV 조회 불필요 — {@code resolvePercentile()}에서 이미 100.0 fallback이 적용되므로
 * WARN 없이 {@code ESTABLISHED_FALLBACK_YEARS}를 반환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
class ListedYearsResolver {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 상장일 미상 + OHLCV MIN 없음(신규 IPO 등): 보수적 신규 처리용 값 (A 등급 임계 미만)
    static final double NEW_STOCK_FALLBACK_YEARS = 0.0;
    // 상장일 미상 + OHLCV MIN >= 7년: 장기 상장 확인 fallback (A 등급 임계 초과)
    static final double ESTABLISHED_FALLBACK_YEARS = 100.0;

    private final DailyOhlcvRepository dailyOhlcvRepository;

    /**
     * 종목의 경과 상장 연수를 반환한다.
     *
     * @param stock 대상 종목
     * @return 경과 연수 (double)
     */
    double resolve(Stock stock) {
        LocalDate listedDate = stock.getListedDate();
        if (listedDate != null) {
            return ChronoUnit.DAYS.between(listedDate, LocalDate.now(KST)) / 365.25;
        }

        // INDEX/COMMODITY: OHLCV 조회 불필요, WARN 없이 장기 상장 fallback 반환
        if (stock.getAssetType() == AssetType.INDEX
                || stock.getAssetType() == AssetType.COMMODITY) {
            return ESTABLISHED_FALLBACK_YEARS;
        }

        // listedDate=NULL: OHLCV MIN(trade_date) 근사
        Optional<LocalDate> minTradeDate =
                dailyOhlcvRepository.findMinTradeDateByStockId(stock.getId());

        if (minTradeDate.isEmpty()) {
            log.warn(
                    "상장일 미상 + OHLCV 없음 — 보수적 신규로 간주(A 미수여)"
                            + " — symbol={}, market={}, assetType={}",
                    stock.getSymbol(),
                    stock.getMarket(),
                    stock.getAssetType());
            return NEW_STOCK_FALLBACK_YEARS;
        }

        double ohlcvElapsedYears =
                ChronoUnit.DAYS.between(minTradeDate.get(), LocalDate.now(KST)) / 365.25;

        if (ohlcvElapsedYears >= 7.0) {
            log.warn(
                    "상장일 미상 + OHLCV MIN >= 7년 — 장기 상장으로 간주(A 등급 후보), 신규 종목 오분류 여부 확인 요망"
                            + " — symbol={}, market={}, assetType={}, ohlcvMinDate={}",
                    stock.getSymbol(),
                    stock.getMarket(),
                    stock.getAssetType(),
                    minTradeDate.get());
            return ESTABLISHED_FALLBACK_YEARS;
        }

        log.warn(
                "상장일 미상 + OHLCV MIN < 7년 — 신규로 간주(A 미수여)"
                        + " — symbol={}, market={}, assetType={}, ohlcvMinDate={}, elapsedYears={}",
                stock.getSymbol(),
                stock.getMarket(),
                stock.getAssetType(),
                minTradeDate.get(),
                String.format("%.2f", ohlcvElapsedYears));
        return ohlcvElapsedYears;
    }
}
