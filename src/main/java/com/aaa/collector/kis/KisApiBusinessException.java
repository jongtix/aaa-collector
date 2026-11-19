package com.aaa.collector.kis;

/**
 * KIS API가 {@code rt_cd != "0"} 응답을 반환할 때 발생하는 비즈니스 오류 예외.
 *
 * <p>인증 실패, 잘못된 그룹코드 등 결정론적(deterministic) 오류이므로 재시도해도 동일하게 실패한다. {@link
 * com.aaa.collector.common.retry.RetryExecutor}의 예외 분류기에서 permanent(즉시 전파)로 판정해야 한다. 단, 게이트 측 예외
 * 분류기가 {@link #getMsgCd()}로 식별하는 일시적 게이트웨이 오류(예: OPSQ0008 호출 후처리 오류)는 예외로 재시도 대상이다.
 *
 * <p>{@link com.aaa.collector.kis.token.KisApiResponseException}(토큰 발급 전용)과는 별개의 관심사다.
 */
public class KisApiBusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String msgCd;

    public KisApiBusinessException(String rtCd, String msgCd, String msg1) {
        super("KIS API 비즈니스 오류 — rt_cd=%s, msg_cd=%s, msg1=%s".formatted(rtCd, msgCd, msg1));
        this.msgCd = msgCd;
    }

    /** KIS 응답의 {@code msg_cd} — 재시도 가능한 일시 오류 식별에 사용한다. */
    public String getMsgCd() {
        return msgCd;
    }
}
