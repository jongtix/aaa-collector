package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 한 윈도우 수집 결과 — 종료 판정 입력 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>순수 값 객체. {@link BackfillTerminationPolicy}에 전달되어 그룹별 종료 판정을 구동한다. 그룹 구분은 data_table이 아니라
 * {@link BackfillGroup}로 미리 분류해 전달한다 — [CR-02] {@code short_sale_domestic}은 그룹 B다.
 *
 * @param group 종료 규칙 그룹(A=daily_ohlcv만 / B=공매도·investor·credit)
 * @param rowCount 직전 윈도우 저장(검증 통과) 행 수 — 메트릭·last_row_count·그룹 B 판정 입력
 * @param rawRowCount 직전 윈도우 KIS 원본 응답 행수(거부 전) — 그룹 A 종료 전용. 그룹 B는 {@code rowCount}와 동일
 * @param oldestTradeDate 직전 윈도우가 반환한 최소(가장 과거) 거래일. 0건이면 {@code null}
 * @param previousOldest 그 이전 윈도우의 최소 거래일(전진 판정 기준). 최초 윈도우면 {@code null}
 * @param previousRowCount 그 이전 윈도우의 응답 행 수(클램프 의심 판정 기준). 그룹 A는 미사용
 * @param currentStaleCount 현재까지 누적된 연속 무전진 횟수(그룹 B). 그룹 A는 미사용
 */
// @MX:NOTE: [AUTO] rawRowCount = GROUP_A 종료 전용 입력. groupB·구 groupA 2-arg는 rawRowCount=rowCount 기본.
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-083,-088 — GROUP_B 메트릭·종료·clamp 불변 보장.
public record BackfillWindowOutcome(
        BackfillGroup group,
        int rowCount,
        int rawRowCount,
        LocalDate oldestTradeDate,
        LocalDate previousOldest,
        Integer previousRowCount,
        int currentStaleCount) {

    /**
     * 그룹 A(daily_ohlcv) 윈도우 결과 — 원본 행수를 종료 입력으로 명시(SPEC-COLLECTOR-BACKFILL-006).
     *
     * @param rowCount 저장(검증 통과) 행 수
     * @param rawRowCount KIS 원본 응답 행수(거부 전) — 종료 판정 입력
     * @param oldestTradeDate 최소 거래일(0건이면 {@code null})
     * @return 그룹 A 결과
     */
    public static BackfillWindowOutcome groupA(
            int rowCount, int rawRowCount, LocalDate oldestTradeDate) {
        return new BackfillWindowOutcome(
                BackfillGroup.GROUP_A, rowCount, rawRowCount, oldestTradeDate, null, null, 0);
    }

    /**
     * 그룹 A 윈도우 결과(레거시 2-arg) — {@code rawRowCount = rowCount}로 기본 설정한다.
     *
     * @param rowCount 응답 행 수 ({@code rawRowCount}로도 동일 사용)
     * @param oldestTradeDate 최소 거래일(0건이면 {@code null})
     * @return 그룹 A 결과
     */
    public static BackfillWindowOutcome groupA(int rowCount, LocalDate oldestTradeDate) {
        return groupA(rowCount, rowCount, oldestTradeDate);
    }

    /**
     * 그룹 C({@code corporate_events}, SPLIT) 윈도우 결과 — 커서완주·단일콜 소진(SPEC-COLLECTOR-BACKFILL-GROUPC-001
     * REQ-GC-001/004).
     *
     * <p>{@link BackfillTerminationPolicy#decide}가 GROUP_C 결과의 필드를 일절 참조하지 않고 무조건 완료를 반환하므로, 최소
     * 무해값(전부 0/null)으로 채운다.
     *
     * @return 그룹 C 결과 (필드 무의미 — decide가 미참조)
     */
    public static BackfillWindowOutcome groupC() {
        return new BackfillWindowOutcome(BackfillGroup.GROUP_C, 0, 0, null, null, null, 0);
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
        // 그룹 B는 rawRowCount를 산정하지 않는다 — rowCount(저장 행수)와 동일 설정해 종료·clamp·메트릭 불변(REQ-BACKFILL-088).
        return new BackfillWindowOutcome(
                BackfillGroup.GROUP_B,
                rowCount,
                rowCount,
                oldestTradeDate,
                previousOldest,
                previousRowCount,
                currentStaleCount);
    }
}
