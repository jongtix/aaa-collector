package com.aaa.collector.backfill;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import jakarta.persistence.Converter;

/** {@link BackfillStatusType} JPA 컨버터. */
@Converter(autoApply = true)
public class BackfillStatusConverter extends AbstractStringEnumConverter<BackfillStatusType> {

    public BackfillStatusConverter() {
        super(BackfillStatusType.class);
    }
}
