package com.aaa.collector.common.exception;

/**
 * aaa-collector 서비스의 최상위 런타임 예외.
 *
 * <p>모든 도메인 예외는 이 클래스를 상속하여 일관된 예외 계층 구조를 형성한다.
 */
public abstract class CollectorException extends RuntimeException {

    /**
     * @param message 예외 메시지
     */
    protected CollectorException(String message) {
        super(message);
    }

    /**
     * @param message 예외 메시지
     * @param cause 원인 예외
     */
    protected CollectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
