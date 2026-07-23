package com.aaa.collector.market.calendar;

/**
 * {@code market_calendar} 행 값의 출처 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-003).
 *
 * <p>선언 순서 = 우선순위 내림차순이며 {@link #ordinal()} 기반으로 비교한다 — {@link #MANUAL}이 최우선, {@link #DERIVED}가
 * 최하위다. 자동 갱신 경로({@code MarketCalendarService#upsert})는 신규 값의 우선순위가 기존 행보다 낮으면(ordinal 값이 더 크면) 절대
 * 덮어쓰지 않는다(REQ-CAL-004) — 운영자 수동 정정이 다음날 자동 갱신으로 되돌아가지 않는다.
 */
public enum CalendarSource {

    /** 운영자 수동 정정 — 최우선. */
    MANUAL,

    /** KIS {@code chk-holiday}(CTCA0903R) 응답. */
    KIS_API,

    /** NYSE 결정론 알고리즘({@code NyseHolidayAlgorithm}) 계산. */
    ALGORITHM,

    /** {@code daily_ohlcv} 시장 전체 존재 여부로 유도한 값 — 최저 우선순위. */
    DERIVED
}
