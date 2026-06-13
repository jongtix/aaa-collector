package com.aaa.collector.common.startup;

import com.aaa.collector.common.exception.CollectorException;

/**
 * collector DB 권한 self-check 실패 시 발생하는 예외.
 *
 * <p>기동 시 기대 권한({@code SELECT, INSERT on aaa.*} + 4개 Tier-2 테이블 {@code UPDATE})이 하나라도 누락되면 {@link
 * DbGrantCheckRunner}가 이 예외를 던져 Spring Boot 기동을 즉시 실패(fail-fast)시킨다.
 *
 * @see DbGrantCheckRunner
 * @see DbGrantVerifier
 */
public class DbGrantMissingException extends CollectorException {

    /**
     * @param message 누락된 권한/테이블 목록을 포함한 상세 메시지
     */
    public DbGrantMissingException(String message) {
        super(message);
    }
}
