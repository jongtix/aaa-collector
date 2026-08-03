package com.aaa.collector.macro.ecos;

/**
 * ECOS API가 {@code RESULT.CODE=ERROR-*}를 반환하거나 예측 불가한 응답(본문 {@code null} 또는 {@code RESULT}·{@code
 * StatisticSearch} 어느 키도 없음)을 반환할 때 발생하는 예외 (SPEC-COLLECTOR-ECOS-DATEFMT-001 REQ-ECOSFMT-008/010).
 *
 * <p>예외 메시지에는 {@code indicator_code}·{@code RESULT.CODE}·{@code RESULT.MESSAGE}를 포함하되 {@code
 * serviceKey}는 노출하지 않는다(REQ-ECOSFMT-011, AC-2.6).
 */
public class EcosApiException extends RuntimeException {

    public EcosApiException(String indicatorCode, String code, String message) {
        super(
                "ECOS API 오류 — indicator_code=%s, RESULT.CODE=%s, RESULT.MESSAGE=%s"
                        .formatted(indicatorCode, code, message));
    }

    public EcosApiException(String indicatorCode, String reason) {
        super("ECOS API 예측 불가 응답 — indicator_code=%s, reason=%s".formatted(indicatorCode, reason));
    }
}
