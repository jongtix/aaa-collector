package com.aaa.collector.stock;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.stock.enums.PeriodType;
import jakarta.persistence.Converter;

/** {@link PeriodType} JPA 컨버터. */
@Converter(autoApply = true)
public class PeriodTypeConverter extends AbstractStringEnumConverter<PeriodType> {

    public PeriodTypeConverter() {
        super(PeriodType.class);
    }
}
