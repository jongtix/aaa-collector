package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import java.time.LocalDate;
import java.util.ArrayList;
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
        return collectDailyForBackfillWithRaw(date).kept();
    }

    /**
     * 백필용 단일 날짜 수집 — kept/raw 노출판 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-051, TASK-006a/-006).
     *
     * <p>{@link #collectDailyForBackfill(LocalDate)}의 기존 시그니처·호출처는 그대로 두고(기존 backward walk 무회귀),
     * 정방향 갭 walk({@code UsdkrwCoveredGapFiller})가 필요로 하는 raw(검증 전 원본 행수)까지 함께 노출하는 신규 메서드를 추가했다 —
     * 신규 fetch 메서드는 아니며 동일한 {@code usdkrwChain.fetchDaily} 경로를 재사용한다. {@code kept}는 기존 반환값과 동일하게
     * {@code batch.size()}(검증 통과·저장 시도 행수)이며, §2.6 kept 정의(중복 삽입 시도 포함, 순 신규 삽입 무관)와 이미 일치함을
     * TASK-006a에서 실측 확인했다 — 별도 변환 없이 그대로 노출한다.
     *
     * @param date 수집 대상 날짜
     * @return kept(검증 통과·저장 시도 행수)/raw(검증 전 원본 응답 행수)
     */
    public SaveOutcome collectDailyForBackfillWithRaw(LocalDate date) {
        List<MarketIndicatorRow> rows = usdkrwChain.fetchDaily(date);
        return saveRowsWithRaw(rows);
    }

    private int saveRows(List<MarketIndicatorRow> rows) {
        return saveRowsWithRaw(rows).kept();
    }

    private SaveOutcome saveRowsWithRaw(List<MarketIndicatorRow> rows) {
        // REQ-INSERT-009: 유효 행 누적 후 단일 배치 INSERT IGNORE (소용량 — 분할 불필요)
        List<MarketIndicator> batch = new ArrayList<>();
        for (MarketIndicatorRow row : rows) {
            if (!isValid(row)) {
                log.warn("[usdkrw] 행 검증 실패 — skip: {}", row);
                continue;
            }
            batch.add(toEntity(row));
        }
        if (!batch.isEmpty()) {
            marketIndicatorInserter.insertBatch(batch);
        }
        return new SaveOutcome(batch.size(), rows.size());
    }

    /**
     * {@link #saveRowsWithRaw} 저장 결과 (SPEC-COLLECTOR-BACKFILL-011 §2.6).
     *
     * @param kept 검증 통과·저장 시도 행수(§2.6 kept, 중복 삽입 시도 포함)
     * @param raw 검증 전 원본 응답 행수(§2.6 raw)
     */
    public record SaveOutcome(int kept, int raw) {}

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
