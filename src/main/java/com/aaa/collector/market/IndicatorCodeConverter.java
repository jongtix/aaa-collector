package com.aaa.collector.market;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.market.enums.IndicatorCode;
import jakarta.persistence.Converter;

/** {@link IndicatorCode} JPA 컨버터. */
@Converter(autoApply = true)
public class IndicatorCodeConverter extends AbstractStringEnumConverter<IndicatorCode> {

    public IndicatorCodeConverter() {
        super(IndicatorCode.class);
    }
}
