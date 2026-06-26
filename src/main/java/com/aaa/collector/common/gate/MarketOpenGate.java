package com.aaa.collector.common.gate;

import java.time.LocalDate;

/**
 * 국내 시장 개장일·장중 판정 인터페이스.
 *
 * <p>구현체({@code MarketSessionGate})는 KIS 캘린더를 참조하며, 미로드·날짜 없음 상황에서는 안전 방향인 {@code true}를 반환한다 —
 * stale 캘린더가 수집을 부당하게 억제하지 않도록.
 *
 * <p>이 인터페이스를 {@code common} 패키지에 두어 {@code stock}·{@code kis} 슬라이스가 {@code market} 슬라이스에 직접 의존하지
 * 않고 게이트를 참조할 수 있도록 한다 (ArchUnit 순환 의존성 방지, CR-01).
 */
public interface MarketOpenGate {

    /**
     * 지정 날짜가 KIS 캘린더 기준 개장일인지 반환한다.
     *
     * @param date 판정할 날짜 (KST)
     * @return 개장일이면 {@code true}, 휴장일이면 {@code false}
     */
    boolean isOpenDay(LocalDate date);

    /**
     * 현재 시각 기준 국내 장이 열려 있는지 반환한다.
     *
     * <p>boot-unset(캘린더 미로드) 시 schedule-only(시간·요일만)로 판정한다(fail-open). 이는 부팅 시점 복구 로직의 수용된
     * 트레이드오프다(REQ-WSREC-030).
     *
     * <p>이 메서드를 {@code common.gate} 인터페이스에 선언하여 {@code kis} 슬라이스가 {@code market} 슬라이스를 직접 의존하지 않도록
     * 한다 (ArchUnit 순환 의존성 방지 — CR-01, SPEC-COLLECTOR-WS-RECOVERY-001).
     *
     * @return 장중이면 {@code true}, 장외·주말·휴장이면 {@code false}
     */
    boolean isMarketOpenNow();
}
