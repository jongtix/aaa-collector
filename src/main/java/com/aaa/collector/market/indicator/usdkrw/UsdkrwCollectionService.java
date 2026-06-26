package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
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
 * USDKRW 일봉 수집 서비스 (SPEC-COLLECTOR-MARKETIND-001, REQ-010~016).
 *
 * <p>체인 = [KOREAEXIM, (ECOS 포트), Yahoo USDKRW=X]. 행별 검증 skip(REQ-034). 종 단위 예외 흡수(REQ-003).
 */
@Slf4j
@Service
public class UsdkrwCollectionService {

    private final MarketIndicatorSourceChain usdkrwChain;
    private final MarketIndicatorRepository marketIndicatorRepository;
    private final MarketIndicatorInserter marketIndicatorInserter;

    public UsdkrwCollectionService(
            @Qualifier("usdkrwChain") MarketIndicatorSourceChain usdkrwChain,
            MarketIndicatorRepository marketIndicatorRepository,
            MarketIndicatorInserter marketIndicatorInserter) {
        this.usdkrwChain = usdkrwChain;
        this.marketIndicatorRepository = marketIndicatorRepository;
        this.marketIndicatorInserter = marketIndicatorInserter;
    }

    /**
     * 당일 USDKRW 일봉 수집.
     *
     * @param date 수집 대상 날짜
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 종 단위 예외 격리 (REQ-003)
    public void collectDaily(LocalDate date) {
        try {
            List<MarketIndicatorRow> rows = usdkrwChain.fetchDaily(date);
            int saved = saveRows(rows);
            log.info("[usdkrw] 일봉 수집 완료 — date={}, saved={}", date, saved);
        } catch (Exception e) {
            log.error("[usdkrw] 일봉 수집 예외 — date={}", date, e);
        }
    }

    /**
     * 전체 이력 수집 (백필용).
     *
     * <p>KOREAEXIM은 전체 이력 단일 호출 미지원(fetchHistory()=빈 리스트)이므로 백필은 날짜 루프({@link
     * #collectDailyForBackfill})로 수행한다.
     *
     * @return 저장된 행 수
     */
    public int collectHistory() {
        List<MarketIndicatorRow> rows = usdkrwChain.fetchHistory();
        return saveRows(rows);
    }

    /**
     * 백필용 단일 날짜 수집.
     *
     * @param date 수집 대상 날짜
     * @return 저장된 행 수
     */
    public int collectDailyForBackfill(LocalDate date) {
        List<MarketIndicatorRow> rows = usdkrwChain.fetchDaily(date);
        return saveRows(rows);
    }

    private int saveRows(List<MarketIndicatorRow> rows) {
        int count = 0;
        for (MarketIndicatorRow row : rows) {
            if (!isValid(row)) {
                log.warn("[usdkrw] 행 검증 실패 — skip: {}", row);
                continue;
            }
            marketIndicatorInserter.insertBatch(List.of(toEntity(row)));
            count++;
        }
        return count;
    }

    private boolean isValid(MarketIndicatorRow row) {
        return row.closeValue() != null && row.closeValue().signum() > 0;
    }

    private MarketIndicator toEntity(MarketIndicatorRow row) {
        return MarketIndicator.builder()
                .indicatorCode(IndicatorCode.USDKRW)
                .tradeDate(row.tradeDate())
                .openValue(row.openValue())
                .highValue(row.highValue())
                .lowValue(row.lowValue())
                .closeValue(row.closeValue())
                .source(row.source())
                .build();
    }
}
