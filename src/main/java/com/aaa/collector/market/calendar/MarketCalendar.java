package com.aaa.collector.market.calendar;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시장 개장일/휴장일 캘린더 엔티티 (SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-001/-002).
 *
 * <p>복합 키 {@code (calendarCode, calDate)}({@link MarketCalendarId}) — 시딩·갱신된 범위 내 개장·휴장 불문 모든 날짜에
 * 정확히 1행을 유지한다("휴장일만 저장"하지 않는다). DELETE 경로는 두지 않는다(REQ-CAL-005) — 정정은 {@link
 * MarketCalendarService}의 우선순위 인지 UPDATE로만 수행한다.
 */
@Entity
@Table(name = "market_calendar")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class MarketCalendar extends BaseEntity {

    @EmbeddedId private final MarketCalendarId id;

    @Column(name = "is_open", nullable = false)
    private boolean open;

    @Column(name = "source", length = 16, nullable = false)
    private CalendarSource source;

    @Builder
    private MarketCalendar(
            CalendarCode calendarCode, LocalDate calDate, boolean open, CalendarSource source) {
        super();
        this.id = new MarketCalendarId(calendarCode, calDate);
        this.open = open;
        this.source = source;
    }

    /** {@link MarketCalendarId#getCalendarCode()} 편의 접근자. */
    public CalendarCode getCalendarCode() {
        return id.getCalendarCode();
    }

    /** {@link MarketCalendarId#getCalDate()} 편의 접근자. */
    public LocalDate getCalDate() {
        return id.getCalDate();
    }

    /**
     * 우선순위 판정 후 값을 갱신한다 (REQ-CAL-004) — {@link MarketCalendarService} 전용, JPA dirty-check 경로.
     *
     * <p>우선순위 비교 자체는 호출자({@link MarketCalendarService})의 책임이다 — 이 메서드는 무조건 값을 반영한다.
     *
     * @param open 새 개장 여부
     * @param source 새 출처
     */
    void applyUpdate(boolean open, CalendarSource source) {
        this.open = open;
        this.source = source;
    }
}
