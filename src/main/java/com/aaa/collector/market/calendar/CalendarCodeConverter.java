package com.aaa.collector.market.calendar;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import jakarta.persistence.Converter;

/** {@link CalendarCode} JPA 컨버터 (ADR-010 — {@code @Enumerated} 미사용). */
@Converter(autoApply = true)
public class CalendarCodeConverter extends AbstractStringEnumConverter<CalendarCode> {

    public CalendarCodeConverter() {
        super(CalendarCode.class);
    }
}
