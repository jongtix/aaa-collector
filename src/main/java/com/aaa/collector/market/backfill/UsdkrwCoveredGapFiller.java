package com.aaa.collector.market.backfill;

import com.aaa.collector.backfill.CoveredFillResult;
import com.aaa.collector.backfill.CoveredGapFiller;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import java.time.LocalDate;

/**
 * USDKRW 정방향 갭 walk 1스텝 실행체 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-051, AC-12).
 *
 * <p>단일 날짜형 소스({@link com.aaa.collector.backfill.CoveredTrackingEligibility.Mode#SINGLE_DATE}) —
 * {@code cursor} 하루치를 {@link UsdkrwCollectionService#collectDailyForBackfillWithRaw(LocalDate)}(기존
 * 백필 경로 {@code collectDailyForBackfill}의 kept/raw 노출판)로 취득·적재한다(REQ-CVR-051, 신규 fetch 메서드 없음 —
 * {@code usdkrwChain.fetchDaily}를 그대로 재사용).
 *
 * <p>주말·비거래일 skip은 이 클래스가 아니라 호출자({@link
 * com.aaa.collector.backfill.CoveredRangeService#walkGapForward})가 {@code MarketOpenGate}로 이미
 * 처리한다(TASK-004) — 여기서 중복 구현하지 않는다.
 */
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
public class UsdkrwCoveredGapFiller implements CoveredGapFiller {

    private final UsdkrwCollectionService usdkrwCollectionService;

    public UsdkrwCoveredGapFiller(UsdkrwCollectionService usdkrwCollectionService) {
        this.usdkrwCollectionService = usdkrwCollectionService;
    }

    /**
     * {@code cursor} 하루치를 수집·적재하고 kept/raw를 {@link CoveredFillResult}로 매핑한다.
     *
     * @param cursor 이번 스텝의 거래일
     * @return kept/raw/filledUntil(=cursor, 단일 날짜형이라 스텝=1일)
     */
    @Override
    public CoveredFillResult persistStep(LocalDate cursor) {
        UsdkrwCollectionService.SaveOutcome outcome =
                usdkrwCollectionService.collectDailyForBackfillWithRaw(cursor);
        return new CoveredFillResult(outcome.kept(), outcome.raw(), cursor);
    }
}
