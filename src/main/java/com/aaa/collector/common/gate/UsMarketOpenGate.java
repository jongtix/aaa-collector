package com.aaa.collector.common.gate;

import java.time.LocalDate;

/**
 * 미국 시장 개장일·장중 판정 인터페이스 (SPEC-COLLECTOR-USMKT-001).
 *
 * <p>구현체({@code UsMarketSessionGate})는 NYSE 결정론적 알고리즘으로 휴장일을 계산하며, 초기화 미완료 시 안전 방향인 {@code true}를
 * 반환한다 — 수집 억제 방지(REQ-007).
 *
 * <p>이 인터페이스를 {@code common} 패키지에 두어 {@code kis} 슬라이스가 {@code market} 슬라이스에 직접 의존하지 않고 게이트를 참조할 수
 * 있도록 한다 (ArchUnit 순환 의존성 방지, MarketOpenGate CR-01 패턴 답습).
 */
public interface UsMarketOpenGate {

    /**
     * 지정 날짜가 NYSE 기준 개장일인지 반환한다.
     *
     * @param date 판정할 날짜 (호출자 기준 시간대 — 해외는 ET 기준)
     * @return 개장일이면 {@code true}, 휴장일·주말이면 {@code false}
     */
    boolean isOpenDay(LocalDate date);

    /**
     * 현재 시각 기준 미국 장이 열려 있는지 반환한다.
     *
     * <p>ET 평일 09:25(포함) ~ 16:05(미포함) AND 비NYSE 휴장일이면 {@code true}.
     *
     * <p>이 메서드를 {@code common.gate} 인터페이스에 선언하여 {@code kis} 슬라이스가 {@code market} 슬라이스를 직접 의존하지 않도록
     * 한다 (ArchUnit 순환 의존성 방지 — CR-01 패턴 답습).
     *
     * @return 미장 운영 중이면 {@code true}
     */
    boolean isMarketOpenNow();
}
