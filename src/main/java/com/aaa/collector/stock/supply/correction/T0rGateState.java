package com.aaa.collector.stock.supply.correction;

import java.time.LocalDate;

/**
 * T0R 완료 마커 게이트 상태 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-043~045, plan.md §M7).
 *
 * <p>{@code ShortSaleDomesticCorrectionScheduler}가 매 실행 시작 시 {@code t0r_correction_status}를 1회 조회해
 * 이 상태를 구성하고, Track 1({@link ShortSaleVolRateCorrectionService#correctLegacyBacklog(T0rGateState)})
 * ②단계(가드 판정 이후 원자적 쓰기 직전)와 Track 2({@link
 * ShortSaleVolRateCorrectionService#verifyRecentInserts(T0rGateState)})의 recompute 호출 직전에 동일하게
 * 전달한다.
 *
 * @param active {@code t0r_correction_status.completed_at IS NULL}이면 true — true일 때만 구간 검사(defer)가
 *     발생한다(REQ-T0R-044). false(완료)면 {@link #shouldDefer(LocalDate)}가 항상 false를 반환해 구간 검사를
 *     생략한다(REQ-T0R-045).
 * @param closingWindowEndDate 닫히는 창 상한(inclusive) — {@code active=false}일 때는 사용되지 않는다.
 */
public record T0rGateState(boolean active, LocalDate closingWindowEndDate) {

    /** 게이트 비활성(구간 검사 생략) — completed_at이 이미 NOT NULL이거나, 마커 행 자체가 없는 방어적 fallback. */
    public static T0rGateState inactive() {
        return new T0rGateState(false, null);
    }

    /**
     * 대상 거래일이 이번 실행에서 defer 대상인지 판정한다.
     *
     * <p>{@code active=false}면 항상 false(REQ-T0R-045). {@code active=true}면 {@code trade_date}가
     * {@code [2026-06-29, closingWindowEndDate]}(REQ-T0R-011 하한 리터럴 — {@link
     * ShortSaleDomesticT0RevisionCorrectionService#CLOSING_WINDOW_START_DATE}와 동일 값 재사용) 구간에 속하면
     * true(REQ-T0R-044).
     *
     * @param tradeDate 대상 행의 거래일
     * @return true면 이번 실행에서 defer(acml_vol·vol_rate_verified_at 모두 미기록, 다음 실행에서 자동 재평가)
     */
    public boolean shouldDefer(LocalDate tradeDate) {
        if (!active) {
            return false;
        }
        return !tradeDate.isBefore(
                        ShortSaleDomesticT0RevisionCorrectionService.CLOSING_WINDOW_START_DATE)
                && !tradeDate.isAfter(closingWindowEndDate);
    }
}
