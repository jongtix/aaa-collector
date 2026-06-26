package com.aaa.collector.schedule.catchup;

/**
 * 배치 단위의 freshness 판정 방식 (SPEC-COLLECTOR-CATCHUP-001 T3).
 *
 * <ul>
 *   <li>{@link #INSTANT} — lastLoad Instant과 expectedLastFire Instant을 직접 비교한다. 일봉/수급/공매도 등 하루에 한 번
 *       실행되는 배치.
 *   <li>{@link #DATE} — lastLoad와 expectedLastFire의 날짜(KST 기준)를 비교한다. ETF 대표 종목 재계산 등 날짜 단위로 완료 여부를
 *       판정해야 하는 배치.
 * </ul>
 */
public enum Freshness {
    INSTANT,
    DATE
}
