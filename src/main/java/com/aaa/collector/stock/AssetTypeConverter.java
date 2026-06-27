package com.aaa.collector.stock;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.stock.enums.AssetType;
import jakarta.persistence.Converter;

/** {@link AssetType} JPA 컨버터. */
@Converter(autoApply = true)
public class AssetTypeConverter extends AbstractStringEnumConverter<AssetType> {

    public AssetTypeConverter() {
        super(AssetType.class);
    }
}
