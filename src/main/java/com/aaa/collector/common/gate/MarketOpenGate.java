package com.aaa.collector.common.gate;

import java.time.LocalDate;

/**
 * 국내 시장 개장일 판정 인터페이스 (배치 수집 게이트 전용).
 *
 * <p>구현체({@code MarketSessionGate})는 KIS 캘린더를 참조하며, 미로드·날짜 없음 상황에서는 안전 방향인 {@code true}를 반환한다 —
 * stale 캘린더가 수집을 부당하게 억제하지 않도록.
 *
 * <p>이 인터페이스를 {@code common} 패키지에 두어 {@code stock} 슬라이스가 {@code market} 슬라이스에 직접 의존하지 않고 게이트를 참조할 수
 * 있도록 한다 (ArchUnit 순환 의존성 방지).
 */
@FunctionalInterface
public interface MarketOpenGate {

    /**
     * 지정 날짜가 KIS 캘린더 기준 개장일인지 반환한다.
     *
     * @param date 판정할 날짜 (KST)
     * @return 개장일이면 {@code true}, 휴장일이면 {@code false}
     */
    boolean isOpenDay(LocalDate date);
}
