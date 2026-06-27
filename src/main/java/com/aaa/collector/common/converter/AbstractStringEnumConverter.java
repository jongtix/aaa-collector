package com.aaa.collector.common.converter;

import jakarta.persistence.AttributeConverter;

/**
 * Jakarta Persistence {@link AttributeConverter} 공통 추상 베이스.
 *
 * <p>null 처리와 {@link Enum#valueOf} 기반 역변환을 단일 지점에서 구현한다. 미지 DB 값은 {@link IllegalArgumentException}을
 * 던져 빠른 실패(fail-fast)를 보장한다.
 *
 * @param <E> 변환 대상 enum 타입
 */
public abstract class AbstractStringEnumConverter<E extends Enum<E>>
        implements AttributeConverter<E, String> {

    private final Class<E> enumType;

    protected AbstractStringEnumConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return Enum.valueOf(enumType, dbData);
    }
}
