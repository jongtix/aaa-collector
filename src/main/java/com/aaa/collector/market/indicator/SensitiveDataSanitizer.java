package com.aaa.collector.market.indicator;

/**
 * API Key 등 민감 데이터를 로그에서 마스킹하는 유틸리티 (SPEC-COLLECTOR-MARKETIND-001, CR-01/CR-03/MA-04).
 *
 * <p>KOREAEXIM/FRED 등 query param 방식 API는 URL에 API Key가 포함될 수 있으므로, 예외 메시지 sanitize로 로그 노출을 방지한다.
 */
public final class SensitiveDataSanitizer {

    private SensitiveDataSanitizer() {}

    /**
     * 메시지에서 {@code authkey}, {@code api_key} 쿼리 파라미터 값을 {@code ***}로 마스킹한다.
     *
     * @param msg 원본 메시지 (null 허용)
     * @return 마스킹된 메시지, null이면 {@code "[no message]"}
     */
    public static String sanitize(String msg) {
        if (msg == null) {
            return "[no message]";
        }
        return msg.replaceAll("(?i)(authkey|api_key)=[^&\\s\"]*", "$1=***");
    }
}
