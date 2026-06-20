package com.aaa.collector.market.indicator.vix;

import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * VIX 일봉 수집 서비스 (SPEC-COLLECTOR-MARKETIND-001, REQ-020~022).
 *
 * <p>체인 = [CBOE, FRED, Yahoo^VIX]. 행별 검증 skip(REQ-034). 종 단위 예외 흡수(REQ-003).
 */
@Slf4j
@Service
public class VixCollectionService {

    private final MarketIndicatorSourceChain vixChain;
    private final MarketIndicatorRepository marketIndicatorRepository;

    public VixCollectionService(
            @Qualifier("vixChain") MarketIndicatorSourceChain vixChain,
            MarketIndicatorRepository marketIndicatorRepository) {
        this.vixChain = vixChain;
        this.marketIndicatorRepository = marketIndicatorRepository;
    }

    /**
     * 당일 VIX 일봉 수집.
     *
     * @param date 수집 대상 날짜
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 종 단위 예외 격리 (REQ-003)
    public void collectDaily(LocalDate date) {
        try {
            List<MarketIndicatorRow> rows = vixChain.fetchDaily(date);
            int saved = saveRows(rows);
            log.info("[vix] 일봉 수집 완료 — date={}, saved={}", date, saved);
        } catch (Exception e) {
            log.error("[vix] 일봉 수집 예외 — date={}", date, e);
        }
    }

    /**
     * 전체 이력 수집 (백필용).
     *
     * @return 저장된 행 수
     */
    public int collectHistory() {
        List<MarketIndicatorRow> rows = vixChain.fetchHistory();
        return saveRows(rows);
    }

    private int saveRows(List<MarketIndicatorRow> rows) {
        int count = 0;
        for (MarketIndicatorRow row : rows) {
            if (!isValid(row)) {
                log.warn("[vix] 행 검증 실패 — skip: {}", row);
                continue;
            }
            marketIndicatorRepository.insertIgnoreDuplicate(toEntity(row));
            count++;
        }
        return count;
    }

    private boolean isValid(MarketIndicatorRow row) {
        if (row.closeValue() == null) {
            return false;
        }
        if (row.closeValue().signum() <= 0) {
            return false;
        }
        return true;
    }

    private MarketIndicator toEntity(MarketIndicatorRow row) {
        return MarketIndicator.builder()
                .indicatorCode(IndicatorCode.VIX)
                .tradeDate(row.tradeDate())
                .openValue(row.openValue())
                .highValue(row.highValue())
                .lowValue(row.lowValue())
                .closeValue(row.closeValue())
                .source(row.source())
                .build();
    }
}
