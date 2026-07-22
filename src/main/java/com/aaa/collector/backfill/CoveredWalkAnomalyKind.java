package com.aaa.collector.backfill;

/**
 * 정방향 갭 walk anomaly 종류 (SPEC-COLLECTOR-BACKFILL-011 TASK-013).
 *
 * <p>{@link BackfillMetrics#recordCoveredWalkAnomaly(CoveredWalkAnomalyKind)}의 {@code kind} 태그값을
 * 타입-세이프하게 표현한다. GROUP_A 전용 {@link BackfillMetrics#recordAnomalyFailed()}와는 완전히 분리된 신호다 — 이 enum은
 * {@link CoveredRangeService#executeStep} 내부에서만 사용된다.
 */
public enum CoveredWalkAnomalyKind {

    /** 앞단 도달 검증 이상 — {@code oldest > cursor}(REQ-CVR-076, 심층 방어). */
    FRONT_GAP("front_gap"),

    /** 검증 전량 실패 이상 — {@code raw > 0 && kept == 0}(REQ-CVR-031). */
    ALL_REJECTED("all_rejected");

    private final String tag;

    CoveredWalkAnomalyKind(String tag) {
        this.tag = tag;
    }

    /** Micrometer {@code kind} 태그에 사용할 문자열 값을 반환한다. */
    public String tagValue() {
        return tag;
    }
}
