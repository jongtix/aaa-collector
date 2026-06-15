package com.aaa.collector.stock.grade;

/**
 * 등급 분류 공통 상수 (SPEC-COLLECTOR-STOCKMETA-001, SPEC-COLLECTOR-GRADE-002).
 *
 * <p>GradeClassifier와 ListedYearsResolver 양쪽에서 참조하는 임계값을 단일 출처로 관리한다.
 */
// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
final class GradeConstants {

    /** A 등급 후보 최소 상장 경과 연수 (7년 이상). */
    static final double ESTABLISHED_YEARS_THRESHOLD = 7.0;

    private GradeConstants() {
        // 상수 홀더 — 인스턴스화 금지
    }
}
