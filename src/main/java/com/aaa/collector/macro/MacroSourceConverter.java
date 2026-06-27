package com.aaa.collector.macro;

import com.aaa.collector.common.converter.AbstractStringEnumConverter;
import com.aaa.collector.macro.enums.MacroSource;
import jakarta.persistence.Converter;

/** {@link MacroSource} JPA 컨버터. */
@Converter(autoApply = true)
public class MacroSourceConverter extends AbstractStringEnumConverter<MacroSource> {

    public MacroSourceConverter() {
        super(MacroSource.class);
    }
}
