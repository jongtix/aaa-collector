package com.aaa.collector.kis.token;

import com.aaa.collector.common.exception.CollectorException;

/**
 * KIS API 토큰 발급 최종 실패 시 발생하는 예외.
 *
 * <p>최대 재시도 횟수({@code MAX_ATTEMPTS})를 모두 소진한 후에도 토큰을 발급받지 못한 경우 던진다.
 */
public class KisTokenIssueException extends CollectorException {

    /**
     * @param alias 토큰 발급에 실패한 계정 식별자
     * @param cause 마지막 시도에서 발생한 원인 예외
     */
    public KisTokenIssueException(String alias, Throwable cause) {
        super("토큰 발급 실패: " + alias, cause);
    }
}
