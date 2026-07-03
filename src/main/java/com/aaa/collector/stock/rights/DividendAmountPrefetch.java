package com.aaa.collector.stock.rights;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 03+75 병합 완료된 최종 금액 맵과 프리페치 관측 카운터 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-012, 062,
 * aaa-infra#64 REQ-ODA-070~072).
 *
 * @param amountsByKey {@code (symbol, acpl_bass_dt) → List<금액항목>}(경로 A — 03·75 병존 시 둘 다 보존)
 * @param scripDividendDates 권리유형 74(스크립/주식배당)로 확정(dfnt_yn=Y) 매칭된 {@code (symbol, acpl_bass_dt)} 키
 *     집합(aaa-infra#64, REQ-ODA-071). 74는 {@code cash_alct_rt}/{@code alct_frcr_unpr}가 0으로 오기 때문에
 *     {@link #amountsByKey}에는 절대 병합하지 않는다 — 저장 대상이 아니라 관측(날짜만 보존)이 목적이며, 03/75와 달리 defer가 영구적이다(다음
 *     배치에도 확정될 수 없음).
 * @param prefetchTruncated MAX_PAGES 절단으로 폐기된 유형 수(0~3, 03/75/74)
 * @param prefetchFailed 페이징 도중 실패로 폐기된 유형 수(0~3, 03/75/74)
 */
record DividendAmountPrefetch(
        Map<DividendAmountKey, List<DividendAmountItem>> amountsByKey,
        Set<DividendAmountKey> scripDividendDates,
        int prefetchTruncated,
        int prefetchFailed) {

    /** 유형 폐기가 하나라도 있으면 맵이 불완전(degraded)하다 — 행별 skippedUnconfirmed 이중 집계 억제 신호(D5). */
    boolean degraded() {
        return prefetchTruncated > 0 || prefetchFailed > 0;
    }
}
