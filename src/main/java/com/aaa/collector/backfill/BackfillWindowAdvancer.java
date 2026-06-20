package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 백필 윈도우 anchor 전진기 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>순수 로직 — KIS/Spring·외부 거래일 캘린더 비의존.
 *
 * <ul>
 *   <li><b>anchor 전진</b>: 다음 anchor = 직전 윈도우 최소 거래일 − 1 달력일(REQ-015, MA-02). 단순 {@code
 *       minusDays(1)} — 영업일 계산이 아니다. KIS가 비거래일을 자동 스킵해 그 이전 거래일부터 반환한다.
 *   <li><b>그룹 A SPAN</b>: 단일 호출 상한 100거래일을 채우기 위해 anchor로부터 ≥150 달력일 이전을 from-date로 제공한다(REQ-013a,
 *       MA-01) — SPAN 부족 오종료(AC-1.2b) 방지. 해외 BYMD는 anchor만 의미가 있으므로 from-date는 국내 기간 윈도우용이다.
 *   <li><b>그룹 B anchor 거부(rt_cd=2)</b>: 무전진이 아니라 anchor를 −1 달력일씩 보정해 영업일에 닿을 때까지 한도 ({@code
 *       anchor-skip-max}) 내 재시도한다(REQ-016).
 * </ul>
 *
 * <p>SPAN(기본 150 달력일)·anchor-skip-max(기본 10)는 생성자 주입 — T7 {@code BackfillProperties}가 주입하기 전까지 호출자가
 * 직접 전달한다.
 */
public final class BackfillWindowAdvancer {

    /** 그룹 A 기간 윈도우 from-date 폭(달력일). 100거래일 충족 위해 ≥150 보장. */
    private final int spanCalendarDays;

    /** 그룹 B rt_cd=2 anchor 보정 최대 시도 횟수. */
    private final int anchorSkipMax;

    /**
     * @param spanCalendarDays 그룹 A SPAN 폭(기본 150 달력일)
     * @param anchorSkipMax 그룹 B anchor 보정 한도(기본 10)
     */
    public BackfillWindowAdvancer(int spanCalendarDays, int anchorSkipMax) {
        this.spanCalendarDays = spanCalendarDays;
        this.anchorSkipMax = anchorSkipMax;
    }

    /**
     * 다음 윈도우 anchor = 최소 거래일 − 1 달력일(REQ-015, MA-02).
     *
     * @param oldestTradeDate 직전 윈도우가 반환한 최소 거래일
     * @return 다음 anchor(−1 달력일)
     */
    public LocalDate nextAnchor(LocalDate oldestTradeDate) {
        return oldestTradeDate.minusDays(1);
    }

    /**
     * 그룹 A 기간 윈도우 from-date — anchor로부터 ≥150 달력일 이전(REQ-013a).
     *
     * @param anchor 기간 윈도우 종료일(anchor)
     * @return from-date(anchor − spanCalendarDays)
     */
    public LocalDate groupASpanFromDate(LocalDate anchor) {
        return anchor.minusDays(spanCalendarDays);
    }

    /**
     * 그룹 B anchor 거부(rt_cd=2) 보정 — anchor를 −1 달력일 보정하고 시도 횟수를 1 증가시킨다(REQ-016).
     *
     * <p>무전진으로 집계하지 않는다. 누적 시도가 {@code anchor-skip-max}에 도달하면 {@code exhausted=true} — 호출자는 당 회차
     * skip한다.
     *
     * @param rejectedAnchor rt_cd=2로 거부된 anchor
     * @param attemptsSoFar 지금까지 누적된 보정 시도 횟수
     * @return 보정된 anchor·누적 시도·한도 초과 여부
     */
    public AnchorCorrectionResult correctRejectedAnchor(
            LocalDate rejectedAnchor, int attemptsSoFar) {
        int attempts = attemptsSoFar + 1;
        return new AnchorCorrectionResult(
                rejectedAnchor.minusDays(1), attempts, attempts >= anchorSkipMax);
    }
}
