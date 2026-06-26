package com.aaa.collector.observability;

import java.sql.SQLException;

/**
 * 행별 INSERT IGNORE 실행 실패(SQLException) 시 호출자에게 통지하는 콜백 인터페이스 (REQ-INSERT-007).
 *
 * <p>격리 삽입 경로({@link SilentDropWarningCounter#countDropsPerRowIsolated})가 독성 행을 skip하고 잔여 행을 계속 처리할
 * 때, 실패 행을 호출자에게 통지하여 경고 로깅 등 후속 처리를 위임한다.
 *
 * @param <T> 행 타입
 */
// @MX:ANCHOR: [AUTO] 격리 삽입 실패 콜백 — countDropsPerRowIsolated + 5개 Tier-1 inserter가 소비(fan_in≥3)
// @MX:REASON: REQ-INSERT-007, REQ-INSERT-008 — 독성 행 skip·잔여 행 계속 처리의 알림 계약
@FunctionalInterface
public interface RowFailureHandler<T> {

    /**
     * 행 삽입이 {@link SQLException}으로 실패했을 때 호출된다.
     *
     * <p>구현체는 예외를 전파하면 안 된다 — 격리 삽입 루프가 계속 진행되어야 하기 때문이다. 대신 warn 로그 등 부작용만 수행한다.
     *
     * @param row 삽입에 실패한 행
     * @param e 실패 원인 SQLException
     */
    void onFailure(T row, SQLException e);
}
