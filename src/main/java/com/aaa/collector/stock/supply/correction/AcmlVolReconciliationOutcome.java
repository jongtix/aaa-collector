package com.aaa.collector.stock.supply.correction;

/**
 * {@code acml_vol} 채움 3분기 판정 결과 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-050~057).
 *
 * <p>Track 1(레거시·{@code acml_vol} 결측 가드) 전용이다 — Track 2(신규 삽입 행 상시 스윕)에서는 호출되지 않는다(REQ-SSVC-057).
 * Track 2는 이미 {@code acml_vol}이 존재하는 행만 다루므로 이 판정 자체가 불필요하다.
 */
public enum AcmlVolReconciliationOutcome {

    /** TR04 라이브 재조회로 산출한 rate가 저장값과 일치 — 재조회 {@code acml_vol}을 그대로 채택한다(REQ-SSVC-051). */
    MATCHED,

    /** 분할·병합 등 실제 조정 이벤트로 판단 — 저장 qty/rate 역산으로 {@code acml_vol}을 채택한다(REQ-SSVC-052). */
    EVENT_ADJUSTED,

    /**
     * T+0 예비치 리비전 등으로 판정 불가 — 정정을 스킵한다({@code acml_vol}·{@code vol_rate_verified_at} 미충전,
     * REQ-SSVC-053).
     */
    REVISION_SUSPECTED
}
