package com.aaa.collector.stock.daily;

import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 일봉 OHLCV 불일치 탐지 컴포넌트 (REQ-OHLCV2-010,-011,-012,-020).
 *
 * <p>KIS 재조회값과 DB 저장값을 비교하여 불일치 시 WARN 로그를 발산한다. 행 수정·삭제는 수행하지 않는다.
 *
 * <p>이 컴포넌트를 별도 분리하는 이유: {@link DomesticDailyOhlcvCollectionService}의 PMD CouplingBetweenObjects
 * 임계값(20) 유지 — {@link DailyOhlcv} 타입 의존을 동일 패키지 내로 격리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MismatchDetector {

    private final DailyOhlcvRepository dailyOhlcvRepository;

    /**
     * 주어진 종목·날짜 목록에 대해 DB 조회 후 응답 행과 비교하여 불일치를 로깅한다 (REQ-OHLCV2-010,-011).
     *
     * <p>N+1 방지: IN 절 단일 쿼리로 일괄 조회 (ADR-025 §한계).
     *
     * @param stockId 종목 PK
     * @param symbol KIS 종목코드 (로그 출력용)
     * @param rows 비교 대상 KIS 응답 행 목록
     * @param dateFmt 날짜 파싱용 포매터
     */
    void detectAndLog(
            Long stockId,
            String symbol,
            List<KisDailyOhlcvResponse.DailyOhlcvRow> rows,
            DateTimeFormatter dateFmt) {

        List<LocalDate> dates =
                rows.stream().map(row -> LocalDate.parse(row.stckBsopDate(), dateFmt)).toList();

        // @MX:NOTE: [AUTO] 불일치 탐지 — 종목별 일괄 조회 후 메모리 비교. BigDecimal은 compareTo 사용
        // @MX:REASON: BigDecimal equals는 scale 차이로 오탐 — 75000 vs 75000.0000 → compareTo==0이어야 일치
        List<DailyOhlcv> storedRows =
                dailyOhlcvRepository.findByStockIdAndTradeDateIn(stockId, dates);

        for (KisDailyOhlcvResponse.DailyOhlcvRow row : rows) {
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), dateFmt);
            for (DailyOhlcv stored : storedRows) {
                if (stored.getTradeDate().equals(tradeDate)) {
                    warnIfMismatch(symbol, tradeDate, stored, row);
                    break;
                }
            }
        }
    }

    private void warnIfMismatch(
            String symbol,
            LocalDate tradeDate,
            DailyOhlcv stored,
            KisDailyOhlcvResponse.DailyOhlcvRow row) {

        BigDecimal open = new BigDecimal(row.stckOprc());
        BigDecimal high = new BigDecimal(row.stckHgpr());
        BigDecimal low = new BigDecimal(row.stckLwpr());
        BigDecimal close = new BigDecimal(row.stckClpr());
        long volume = Long.parseLong(row.acmlVol());
        long tradingValue = Long.parseLong(row.acmlTrPbmn());

        boolean mismatch =
                stored.getOpenPrice().compareTo(open) != 0
                        || stored.getHighPrice().compareTo(high) != 0
                        || stored.getLowPrice().compareTo(low) != 0
                        || stored.getClosePrice().compareTo(close) != 0
                        || stored.getVolume() != volume
                        || stored.getTradingValue() != tradingValue;

        if (mismatch) {
            log.warn(
                    "[domestic-daily] 불일치 탐지 — symbol={}, date={}, "
                            + "stored=[open={}, high={}, low={}, close={}, volume={}, tradingValue={}], "
                            + "refetched=[open={}, high={}, low={}, close={}, volume={}, tradingValue={}]",
                    symbol,
                    tradeDate,
                    stored.getOpenPrice(),
                    stored.getHighPrice(),
                    stored.getLowPrice(),
                    stored.getClosePrice(),
                    stored.getVolume(),
                    stored.getTradingValue(),
                    open,
                    high,
                    low,
                    close,
                    volume,
                    tradingValue);
        }
    }
}
