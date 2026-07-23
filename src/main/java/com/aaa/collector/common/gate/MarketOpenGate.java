package com.aaa.collector.common.gate;

import java.time.LocalDate;
import java.util.Optional;

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

    /**
     * {@code market_calendar}(KRX) 전체 시딩 범위(1985~오늘+20)를 검증 전용으로 조회한다(SPEC-COLLECTOR-CALENDAR-001
     * REQ-CAL-032/-038, TASK-009).
     *
     * <p>{@link #isOpenDay(LocalDate)}와 달리 게이트 캐시(REQ-CAL-036 — 오늘−14~오늘+20 좁은 범위)가 아니라 리포지토리를 직접
     * 조회하며, fail-open을 적용하지 않는다 — 값이 없으면 "모름"({@link Optional#empty()})을 명시적으로 반환한다. 과거 정확도가 필요한 신규
     * 소비처(예: 후속 SPEC의 {@code CoveredRangeService} 개정)만 이 메서드를 사용해야 한다 — 기존 {@link
     * #isOpenDay(LocalDate)}는 이 목적으로 재활용하지 않는다(REQ-CAL-038).
     *
     * @param date 판정할 날짜(제한 없음 — 게이트 캐시 범위 밖도 조회 가능)
     * @return 행이 있으면 {@code Optional.of(is_open)}, 없으면 {@link Optional#empty()}("모름")
     */
    @SuppressWarnings("PMD.LinguisticNaming") // REQ-CAL-032가 명시한 이름 — Optional<Boolean> 반환은 의도된 계약
    Optional<Boolean> isOpenDayStrict(LocalDate date);
}
