package com.aaa.collector.backfill;

/**
 * 정방향 갭 walk 판정에 사용할 시장 캘린더 도메인 (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-083, -084).
 *
 * <p>호출처가 자신이 이미 알고 있는 시장 소속(국내/해외)으로 명시 공급한다 — {@code target_type} 문자열을 비교해 도메인을 추론하던 기존 {@code
 * CoveredRangeService.isOpenDay(String, LocalDate)}를 대체한다.
 */
public enum CoveredCalendarDomain {
    DOMESTIC,
    OVERSEAS
}
