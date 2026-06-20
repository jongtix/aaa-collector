package com.aaa.collector.backfill;

/**
 * 종료 판정 결과 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>{@link BackfillTerminationPolicy#decide}의 반환값. 호출자(T6 오케스트레이터)가 status 전이·진행점 갱신·WARN/메트릭 발행에
 * 사용한다.
 *
 * @param completed {@code true}=COMPLETED 전이, {@code false}=IN_PROGRESS 유지
 * @param nextStaleCount 다음 윈도우로 넘길 연속 무전진 횟수(그룹 B). 전진 시 0. 그룹 A는 0
 * @param clampSuspected retention 클램프 의심 종료 여부(REQ-014a). {@code true}면 호출자가 WARN+메트릭 발행
 */
public record TerminationDecision(boolean completed, int nextStaleCount, boolean clampSuspected) {

    /**
     * IN_PROGRESS 유지 결정.
     *
     * @param nextStaleCount 다음으로 넘길 무전진 횟수
     * @return 미종료 결정
     */
    public static TerminationDecision inProgress(int nextStaleCount) {
        return new TerminationDecision(false, nextStaleCount, false);
    }

    /**
     * COMPLETED 종료 결정.
     *
     * @param nextStaleCount 종료 시점의 무전진 횟수
     * @param clampSuspected 클램프 의심 여부
     * @return 종료 결정
     */
    public static TerminationDecision completed(int nextStaleCount, boolean clampSuspected) {
        return new TerminationDecision(true, nextStaleCount, clampSuspected);
    }
}
