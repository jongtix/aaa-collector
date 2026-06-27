package com.aaa.collector.stock.exthours;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import jakarta.persistence.Converter;

/** {@link Session} JPA 컨버터. */
@Converter(autoApply = true)
public class SessionConverter extends AbstractStringEnumConverter<Session> {

    public SessionConverter() {
        super(Session.class);
    }
}
