package com.aaa.collector.common.logging;

/**
 * 로그 마스킹 유틸리티 클래스.
 *
 * <p>MDC에 값을 넣기 전 pre-masking(1차 방어)에 사용한다. 모든 메서드는 stateless이며 thread-safe하다.
 */
public final class LogMaskingUtils {

    static final String MASKED = "****";
    private static final int FRONT_EXPOSE_LENGTH = 4;
    private static final int BACK_EXPOSE_LENGTH = 4;
    private static final int BACK_ONLY_EXPOSE_LENGTH = 2;

    private LogMaskingUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * FRONT_ONLY 마스킹 — 앞 4자만 노출.
     *
     * <p>null 또는 blank 값은 그대로 반환한다. 4자 미만이면 전체 마스킹한다.
     *
     * @param value 마스킹 대상 값
     * @return 마스킹된 값, null/blank는 원본 반환
     */
    public static String maskFrontOnly(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() < FRONT_EXPOSE_LENGTH) {
            return MASKED;
        }
        return value.substring(0, FRONT_EXPOSE_LENGTH) + MASKED;
    }

    /**
     * FRONT_BACK 마스킹 — 앞 4자 + 뒤 4자 노출.
     *
     * <p>null 또는 blank 값은 그대로 반환한다. 8자 미만이면 전체 마스킹한다.
     *
     * @param value 마스킹 대상 값
     * @return 마스킹된 값, null/blank는 원본 반환
     */
    public static String maskFrontBack(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() < FRONT_EXPOSE_LENGTH + BACK_EXPOSE_LENGTH) {
            return MASKED;
        }
        return value.substring(0, FRONT_EXPOSE_LENGTH)
                + MASKED
                + value.substring(value.length() - BACK_EXPOSE_LENGTH);
    }

    /**
     * BACK_ONLY 마스킹 — 뒤 2자리만 노출.
     *
     * <p>null 또는 blank 값은 그대로 반환한다. 2자 이하이면 전체 마스킹한다.
     *
     * @param value 마스킹 대상 값
     * @return 마스킹된 값, null/blank는 원본 반환
     */
    public static String maskBackOnly(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() <= BACK_ONLY_EXPOSE_LENGTH) {
            return MASKED;
        }
        return MASKED + value.substring(value.length() - BACK_ONLY_EXPOSE_LENGTH);
    }

    /**
     * MDC 키에 등록된 MaskingLevel로 자동 디스패치한다.
     *
     * <p>미등록 키는 원본 값을 그대로 반환한다.
     *
     * @param mdcKey MDC 키 이름
     * @param value 마스킹 대상 값
     * @return 마스킹된 값 또는 원본
     */
    public static String mask(String mdcKey, String value) {
        return MaskingLevel.of(mdcKey).map(level -> apply(level, value)).orElse(value);
    }

    static String apply(MaskingLevel level, String value) {
        return switch (level) {
            case FRONT_ONLY -> maskFrontOnly(value);
            case FRONT_BACK -> maskFrontBack(value);
            case BACK_ONLY -> maskBackOnly(value);
        };
    }
}
