package com.aaa.collector.kis;

/**
 * KIS API rate-limit 전용(일시적·재시도 가능) 오류.
 *
 * <p>HTTP 500 + {@code msg_cd == "EGW00201"} 응답 식별 시 {@link KisApiExecutor}의 멀티키 경로에서 던진다. 영구 비즈니스
 * 오류({@code msg_cd} ≠ EGW00201)와 구별하여 재시도 경로를 타도록 한다.
 *
 * @see KisApiExecutor
 */
// @MX:NOTE: [AUTO] EGW00201 식별 전용 예외 — 멀티키 경로에서만 throw, 단일키 경로에서는 throw 안 함
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001
public class KisRateLimitException extends RuntimeException {

    private final String alias;

    /**
     * @param alias 오류가 발생한 앱키 alias
     * @param message 오류 메시지
     */
    public KisRateLimitException(String alias, String message) {
        super(message);
        this.alias = alias;
    }

    /**
     * @return 오류가 발생한 앱키 alias
     */
    public String getAlias() {
        return alias;
    }
}
