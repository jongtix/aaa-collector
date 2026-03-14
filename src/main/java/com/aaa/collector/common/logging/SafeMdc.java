package com.aaa.collector.common.logging;

import org.slf4j.MDC;

/**
 * MDC 래퍼 유틸리티 클래스.
 *
 * <p>{@link MaskingLevel}에 등록된 키는 {@link LogMaskingUtils#mask}를 통해 자동 마스킹 후 MDC에 저장한다. 미등록 키는 원본 값
 * 그대로 MDC에 저장한다.
 *
 * <p>이 클래스를 통해 MDC에 값을 넣으면 1차 방어(pre-masking)가 자동으로 적용된다. {@link MDC#put}을 직접 호출하는 것은
 * MdcArchitectureTest에 의해 금지된다.
 *
 * <p>모든 메서드는 stateless이며 thread-safe하다.
 */
public final class SafeMdc {

    private SafeMdc() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 키에 대응하는 {@link MaskingLevel}이 등록되어 있으면 값을 마스킹한 뒤 MDC에 저장한다. 미등록 키는 원본 값 그대로 MDC에 저장한다.
     *
     * <p>null 값은 마스킹 없이 {@link MDC#put}으로 위임한다.
     *
     * @param key MDC 키 이름 (null 불허)
     * @param value 저장할 값 (null 허용)
     * @throws IllegalArgumentException key가 null인 경우
     */
    public static void put(String key, String value) {
        if (key == null) {
            throw new IllegalArgumentException("MDC key must not be null");
        }
        String maskedValue = (value == null) ? null : LogMaskingUtils.mask(key, value);
        MDC.put(key, maskedValue);
    }

    /**
     * MDC에서 해당 키를 제거한다.
     *
     * <p>{@link MDC#remove}에 위임한다.
     *
     * @param key 제거할 MDC 키 이름 (null 불허)
     * @throws IllegalArgumentException key가 null인 경우
     */
    public static void remove(String key) {
        if (key == null) {
            throw new IllegalArgumentException("MDC key must not be null");
        }
        MDC.remove(key);
    }

    /**
     * MDC의 모든 키를 제거한다.
     *
     * <p>{@link MDC#clear}에 위임한다. 다른 컴포넌트가 설정한 키까지 모두 삭제하므로, 요청/태스크의 최외곽 경계(서블릿 Filter,
     * {@code @Scheduled} 메서드의 {@code finally} 블록 등)에서만 호출해야 한다. 개별 키 정리에는 {@link #remove}를 사용한다.
     */
    public static void clear() {
        MDC.clear();
    }

    /**
     * MDC에서 해당 키의 값을 반환한다.
     *
     * <p>{@link MDC#get}에 위임한다.
     *
     * @param key 조회할 MDC 키 이름
     * @return MDC에 저장된 값, 없으면 null
     */
    public static String get(String key) {
        return MDC.get(key);
    }
}
