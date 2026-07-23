package com.aaa.collector.market.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link MarketCalendar} 영속성 리포지토리 (SPEC-COLLECTOR-CALENDAR-001).
 *
 * <p>{@link #findByCalendarCodeAndCalDateBetween}은 게이트 좁은 범위 캐시 구축(TASK-007/008)과 {@code
 * isOpenDayStrict}(TASK-009) 전체 범위 조회 양쪽에 재사용된다. {@code @EmbeddedId} 복합 키이므로 Spring Data 파생 쿼리는
 * {@code id.}로 시작하는 내부 메서드({@link #findByIdCalendarCodeAndIdCalDate} 등)를 통해서만 생성 가능하다 — {@code
 * default} 메서드로 편의 진입점을 노출해 호출부는 평평한(flat) 이름을 그대로 쓴다.
 *
 * <p>DELETE 관련 커스텀 메서드는 두지 않는다(REQ-CAL-005 — 정정은 {@link MarketCalendarService}의 우선순위 인지 UPDATE로만
 * 수행).
 */
public interface MarketCalendarRepository extends JpaRepository<MarketCalendar, MarketCalendarId> {

    Optional<MarketCalendar> findByIdCalendarCodeAndIdCalDate(
            CalendarCode calendarCode, LocalDate calDate);

    List<MarketCalendar> findByIdCalendarCodeAndIdCalDateBetween(
            CalendarCode calendarCode, LocalDate startInclusive, LocalDate endInclusive);

    /** {@link #findByIdCalendarCodeAndIdCalDate} 편의 진입점(평평한 이름). */
    default Optional<MarketCalendar> findByCalendarCodeAndCalDate(
            CalendarCode calendarCode, LocalDate calDate) {
        return findByIdCalendarCodeAndIdCalDate(calendarCode, calDate);
    }

    /** {@link #findByIdCalendarCodeAndIdCalDateBetween} 편의 진입점(평평한 이름). */
    default List<MarketCalendar> findByCalendarCodeAndCalDateBetween(
            CalendarCode calendarCode, LocalDate startInclusive, LocalDate endInclusive) {
        return findByIdCalendarCodeAndIdCalDateBetween(calendarCode, startInclusive, endInclusive);
    }
}
