package com.aaa.collector.macro.fred;

/**
 * FRED API가 예측 불가한 응답(본문 {@code null})을 반환할 때 발생하는 예외 (SPEC-COLLECTOR-ECOS-DATEFMT-001
 * REQ-FREDFMT-001).
 *
 * <p>예외 메시지에는 {@code indicator_code}를 포함하되 {@code apiKey}(URL 쿼리 파라미터 값)는 노출하지 않는다(REQ-FREDFMT-003,
 * AC-7.4).
 */
public class FredApiException extends RuntimeException {

    public FredApiException(String indicatorCode, String reason) {
        super("FRED API 예측 불가 응답 — indicator_code=%s, reason=%s".formatted(indicatorCode, reason));
    }
}
