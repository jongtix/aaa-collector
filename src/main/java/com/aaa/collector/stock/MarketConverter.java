package com.aaa.collector.stock;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.stock.enums.Market;
import jakarta.persistence.Converter;

/** {@link Market} JPA 컨버터. */
@Converter(autoApply = true)
public class MarketConverter extends AbstractStringEnumConverter<Market> {

    public MarketConverter() {
        super(Market.class);
    }
}
