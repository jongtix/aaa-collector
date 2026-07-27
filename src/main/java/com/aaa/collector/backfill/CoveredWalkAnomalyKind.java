package com.aaa.collector.backfill;

/**
 * 정방향 갭 walk anomaly 종류 (SPEC-COLLECTOR-BACKFILL-011 TASK-013).
 *
 * <p>{@link BackfillMetrics#recordCoveredWalkAnomaly(CoveredWalkAnomalyKind)}의 {@code kind} 태그값을
 * 타입-세이프하게 표현한다. GROUP_A 전용 {@link BackfillMetrics#recordAnomalyFailed()}와는 완전히 분리된 신호다 — 이 enum은
 * {@link CoveredRangeService#executeStep} 내부에서만 사용된다.
 */
public enum CoveredWalkAnomalyKind {

    /**
     * 앞단 도달 검증 이상 — 구간 {@code [cursor, oldest)}에 개장일이 확인됨(REQ-CVR-081, 심층 방어). TASK-018부터 거래일 기준
     * 판정으로 전환되어 의미가 좁아진다(겸용 아님) — "캘린더로 설명되지 않는 진짜 앞단 미도달"만 뜻한다.
     */
    FRONT_GAP("front_gap"),

    /** 검증 전량 실패 이상 — {@code raw > 0 && kept == 0}(REQ-CVR-031). */
    ALL_REJECTED("all_rejected"),

    /**
     * 캘린더 정보 부족 이상 — 구간 {@code [cursor, oldest)} 중 개장 여부를 알 수 없는 날짜가 있거나(REQ-CVR-085), 구간 길이가 탐색
     * 상한을 초과함(REQ-CVR-086). "모름"을 "정상"으로 낙관 해석하지 않고 보수적으로 경보를 유지한다(SPEC-COLLECTOR-BACKFILL-011
     * TASK-018).
     */
    CALENDAR_UNKNOWN("calendar_unknown");

    private final String tag;

    CoveredWalkAnomalyKind(String tag) {
        this.tag = tag;
    }

    /** Micrometer {@code kind} 태그에 사용할 문자열 값을 반환한다. */
    public String tagValue() {
        return tag;
    }
}
