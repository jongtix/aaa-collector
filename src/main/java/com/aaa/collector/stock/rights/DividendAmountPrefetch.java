package com.aaa.collector.stock.rights;

import java.util.List;
import java.util.Map;

/**
 * 03+75 병합 완료된 최종 금액 맵과 프리페치 관측 카운터 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-012, 062).
 *
 * @param amountsByKey {@code (symbol, acpl_bass_dt) → List<금액항목>}(경로 A — 03·75 병존 시 둘 다 보존)
 * @param prefetchTruncated MAX_PAGES 절단으로 폐기된 유형 수(0~2)
 * @param prefetchFailed 페이징 도중 실패로 폐기된 유형 수(0~2)
 */
record DividendAmountPrefetch(
        Map<DividendAmountKey, List<DividendAmountItem>> amountsByKey,
        int prefetchTruncated,
        int prefetchFailed) {

    /** 유형 폐기가 하나라도 있으면 맵이 불완전(degraded)하다 — 행별 skippedUnconfirmed 이중 집계 억제 신호(D5). */
    boolean degraded() {
        return prefetchTruncated > 0 || prefetchFailed > 0;
    }
}
