package com.aaa.collector.stock;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.stock.enums.DelistingReason;
import jakarta.persistence.Converter;

/** {@link DelistingReason} JPA 컨버터. */
@Converter(autoApply = true)
public class DelistingReasonConverter extends AbstractStringEnumConverter<DelistingReason> {

    public DelistingReasonConverter() {
        super(DelistingReason.class);
    }
}
