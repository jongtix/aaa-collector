package com.aaa.collector.stock;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.stock.enums.EventType;
import jakarta.persistence.Converter;

/** {@link EventType} JPA 컨버터. */
@Converter(autoApply = true)
public class EventTypeConverter extends AbstractStringEnumConverter<EventType> {

    public EventTypeConverter() {
        super(EventType.class);
    }
}
