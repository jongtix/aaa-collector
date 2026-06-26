package com.aaa.collector.stock.daily;

import com.aaa.collector.stock.DailyOhlcv;
import com.aaa.collector.stock.DailyOhlcvRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 일봉 OHLCV 불일치 탐지 컴포넌트 (REQ-OHLCV2-010,-011,-012,-020).
 *
 * <p>KIS 재조회값과 DB 저장값의 OHLC(시가·고가·저가·종가)를 비교하여 불일치 시 WARN 로그를 발산한다. 행 수정·삭제는 수행하지 않는다.
 *
 * <p>volume/tradingValue는 비교 대상에서 제외한다. KRX는 장 마감 직후 잠정치를 제공하고 T+1에 최종 확정치로 소급 수정하므로, 16:00 KST 수집
 * 시점의 volume은 구조적으로 확정치와 0~3% 차이가 발생한다. 이 오차는 OHLC 기반 가격 분석 및 LightGBM 예측에 유의미한 영향을 주지 않는다.
 *
 * <p>REQ-INSERT-003: 검증 단계에서 생성된 {@link ParsedOhlcvRow}를 입력으로 받아 재파싱 없이 비교한다 (W-1 불변식).
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
     * <p>REQ-INSERT-003: {@link ParsedOhlcvRow}에서 날짜·가격을 바로 사용 — 문자열 재파싱 없음.
     *
     * @param stockId 종목 PK
     * @param symbol KIS 종목코드 (로그 출력용)
     * @param rows 비교 대상 ParsedOhlcvRow 목록 (검증 단계에서 파싱 완료)
     */
    void detectAndLog(Long stockId, String symbol, List<ParsedOhlcvRow> rows) {

        List<LocalDate> dates = rows.stream().map(ParsedOhlcvRow::tradeDate).toList();

        // @MX:NOTE: [AUTO] 불일치 탐지 — 종목별 일괄 조회 후 메모리 비교. BigDecimal은 compareTo 사용
        // @MX:REASON: BigDecimal equals는 scale 차이로 오탐 — 75000 vs 75000.0000 → compareTo==0이어야 일치
        List<DailyOhlcv> storedRows =
                dailyOhlcvRepository.findByStockIdAndTradeDateIn(stockId, dates);

        for (ParsedOhlcvRow row : rows) {
            for (DailyOhlcv stored : storedRows) {
                if (stored.getTradeDate().equals(row.tradeDate())) {
                    warnIfMismatch(symbol, row, stored);
                    break;
                }
            }
        }
    }

    private void warnIfMismatch(String symbol, ParsedOhlcvRow row, DailyOhlcv stored) {
        boolean mismatch =
                stored.getOpenPrice().compareTo(row.open()) != 0
                        || stored.getHighPrice().compareTo(row.high()) != 0
                        || stored.getLowPrice().compareTo(row.low()) != 0
                        || stored.getClosePrice().compareTo(row.close()) != 0;

        if (mismatch) {
            log.warn(
                    "[domestic-daily] 불일치 탐지 (가격) — symbol={}, date={}, "
                            + "stored=[open={}, high={}, low={}, close={}], "
                            + "refetched=[open={}, high={}, low={}, close={}]",
                    symbol,
                    row.tradeDate(),
                    stored.getOpenPrice(),
                    stored.getHighPrice(),
                    stored.getLowPrice(),
                    stored.getClosePrice(),
                    row.open(),
                    row.high(),
                    row.low(),
                    row.close());
        }
    }
}
