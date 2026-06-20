package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 한 윈도우 수집 결과 — 종료 판정 입력 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>순수 값 객체. {@link BackfillTerminationPolicy}에 전달되어 그룹별 종료 판정을 구동한다. 그룹 구분은 data_table이 아니라
 * {@link BackfillGroup}로 미리 분류해 전달한다 — [CR-02] {@code short_sale_domestic}은 그룹 B다.
 *
 * @param group 종료 규칙 그룹(A=daily_ohlcv만 / B=공매도·investor·credit)
 * @param rowCount 직전 윈도우 응답 행 수
 * @param oldestTradeDate 직전 윈도우가 반환한 최소(가장 과거) 거래일. 0건이면 {@code null}
 * @param previousOldest 그 이전 윈도우의 최소 거래일(전진 판정 기준). 최초 윈도우면 {@code null}
 * @param previousRowCount 그 이전 윈도우의 응답 행 수(클램프 의심 판정 기준). 그룹 A는 미사용
 * @param currentStaleCount 현재까지 누적된 연속 무전진 횟수(그룹 B). 그룹 A는 미사용
 */
public record BackfillWindowOutcome(
        BackfillGroup group,
        int rowCount,
        LocalDate oldestTradeDate,
        LocalDate previousOldest,
        Integer previousRowCount,
        int currentStaleCount) {

    /**
     * 그룹 A(daily_ohlcv) 윈도우 결과.
     *
     * @param rowCount 응답 행 수
     * @param oldestTradeDate 최소 거래일(0건이면 {@code null})
     * @return 그룹 A 결과
     */
    public static BackfillWindowOutcome groupA(int rowCount, LocalDate oldestTradeDate) {
        return new BackfillWindowOutcome(
                BackfillGroup.GROUP_A, rowCount, oldestTradeDate, null, null, 0);
    }

    /**
     * 그룹 B(공매도·investor·credit) 윈도우 결과.
     *
     * @param rowCount 응답 행 수
     * @param oldestTradeDate 최소 거래일(0건이면 {@code null})
     * @param previousOldest 이전 윈도우 최소 거래일
     * @param previousRowCount 이전 윈도우 행 수
     * @param currentStaleCount 현재 누적 무전진 횟수
     * @return 그룹 B 결과
     */
    public static BackfillWindowOutcome groupB(
            int rowCount,
            LocalDate oldestTradeDate,
            LocalDate previousOldest,
            Integer previousRowCount,
            int currentStaleCount) {
        return new BackfillWindowOutcome(
                BackfillGroup.GROUP_B,
                rowCount,
                oldestTradeDate,
                previousOldest,
                previousRowCount,
                currentStaleCount);
    }
}
