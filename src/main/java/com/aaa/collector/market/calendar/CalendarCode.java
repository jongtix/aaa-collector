package com.aaa.collector.market.calendar;

/**
 * 캘린더 도메인 코드 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-001).
 *
 * <p>{@code stock.enums.Market}(KOSPI/KOSDAQ/NYSE/NASDAQ/AMEX)과 의도적으로 분리된 열거형이다 — 캘린더 도메인은 시장 코드보다
 * 조립 단위가 다르다(KOSPI+KOSDAQ → {@link #KRX} 1종을 공유). {@code Market} → {@code CalendarCode} 매핑은 이 SPEC의
 * 범위 밖이다(후속 SPEC 소관).
 */
public enum CalendarCode {

    /** 한국거래소 — KOSPI·KOSDAQ 공유. */
    KRX,

    /** 뉴욕증권거래소. */
    NYSE
}
