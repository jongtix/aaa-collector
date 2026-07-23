package com.aaa.collector.market.calendar;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import jakarta.persistence.Converter;

/** {@link CalendarSource} JPA 컨버터 (ADR-010 — {@code @Enumerated} 미사용). */
@Converter(autoApply = true)
public class CalendarSourceConverter extends AbstractStringEnumConverter<CalendarSource> {

    public CalendarSourceConverter() {
        super(CalendarSource.class);
    }
}
