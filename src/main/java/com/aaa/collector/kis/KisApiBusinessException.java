package com.aaa.collector.kis;

/**
 * KIS API가 {@code rt_cd != "0"} 응답을 반환할 때 발생하는 비즈니스 오류 예외.
 *
 * <p>인증 실패, 잘못된 그룹코드 등 결정론적(deterministic) 오류이므로 재시도해도 동일하게 실패한다. {@code @Retryable}의 {@code
 * retryFor}에서 반드시 제외해야 한다.
 *
 * <p>{@link com.aaa.collector.kis.token.KisApiResponseException}(토큰 발급 전용)과는 별개의 관심사다.
 */
public class KisApiBusinessException extends RuntimeException {

    public KisApiBusinessException(String rtCd, String msgCd, String msg1) {
        super("KIS API 비즈니스 오류 — rt_cd=%s, msg_cd=%s, msg1=%s".formatted(rtCd, msgCd, msg1));
    }
}
