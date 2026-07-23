package com.aaa.collector.market.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@link MarketCalendar}의 복합 키 ({@code @EmbeddedId}, SPEC-COLLECTOR-CALENDAR-001 REQ-CAL-001).
 *
 * <p>{@code @IdClass} 대신 {@code @EmbeddedId}를 사용한다 — Hibernate는 {@code @IdClass} 필드에는 {@code
 * AttributeConverter}를 적용하지 않는(스키마 검증이 enum ordinal 기본 매핑으로 귀결되는) 알려진 제약이 있어, ADR-010 컨버터 규칙을 준수하려면
 * {@code @EmbeddedId}가 필요하다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class MarketCalendarId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Convert(converter = CalendarCodeConverter.class)
    @Column(name = "calendar_code", length = 8)
    private final CalendarCode calendarCode;

    @Column(name = "cal_date")
    private final LocalDate calDate;

    public MarketCalendarId(CalendarCode calendarCode, LocalDate calDate) {
        this.calendarCode = calendarCode;
        this.calDate = calDate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MarketCalendarId that)) {
            return false;
        }
        return calendarCode == that.calendarCode && Objects.equals(calDate, that.calDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(calendarCode, calDate);
    }
}
