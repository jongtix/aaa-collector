package com.aaa.collector.kis.token;

import com.aaa.collector.common.exception.CollectorException;

/**
 * KIS API 응답 검증 실패 시 발생하는 예외.
 *
 * <p>HTTP 200 응답이지만 응답 바디가 유효하지 않은 경우(필수 필드 누락 등)에 던진다. 이 예외는 재시도로 해결될 수 없으므로 즉시 전파한다.
 */
public class KisApiResponseException extends CollectorException {

    /**
     * @param alias 응답 검증에 실패한 계정 식별자
     * @param detail 검증 실패 상세 내용
     */
    public KisApiResponseException(String alias, String detail) {
        super("[" + alias + "] KIS API 응답 검증 실패: " + detail);
    }

    /**
     * @param alias 응답 검증에 실패한 계정 식별자
     * @param detail 검증 실패 상세 내용
     * @param cause 원인 예외
     */
    public KisApiResponseException(String alias, String detail, Throwable cause) {
        super("[" + alias + "] KIS API 응답 검증 실패: " + detail, cause);
    }
}
