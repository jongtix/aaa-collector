package com.aaa.collector.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * fixture 정리 전용 root 커넥션 유틸 (SPEC-COLLECTOR-DBGRANT-003 M2-T3).
 *
 * <p>DELETE/TRUNCATE 의존 fixture 4개 파일({@code BackfillStatusRepositoryTest}, {@code
 * BackfillStatusSeederTest}, {@code DailyOhlcvRepositoryAdtvIT}, {@code
 * BackfillWindowExecutorTransactionTest})의 {@code @BeforeEach} 정리를 앱 JPA 리포지토리(테스트 대상 datasource
 * 경유) 대신 root 커넥션으로 재배선한다. M2-T4에서 앱 datasource가 {@code collector} 계정(SELECT/INSERT/일부 UPDATE만 보유,
 * DELETE 없음)으로 전환된 뒤에도 fixture 정리가 계속 동작하도록 하는 선행 배선이다(REQ-DBGRANT3-013). 테스트 대상 코드 경로(리포지토리·서비스 호출
 * 자체)는 이 유틸을 거치지 않고 여전히 앱 datasource를 그대로 사용한다 — 이 유틸은 픽스처 정리 전용이다.
 *
 * <p>{@link SharedMySqlContainer#rootDataSourceFor(String)}를 재사용해 root 자격증명을 이 클래스 밖으로 노출하지 않는다.
 *
 * <p>각 메서드는 대상 테이블명을 완전한 컴파일타임 리터럴로 {@code Statement.execute}에 직접 전달한다(동적 테이블명 파라미터를 받는 공용 헬퍼로 통합하지
 * 않음) — GRANT 문과 달리 이 DELETE 문들은 파라미터화 여지가 없는 것은 같지만, 호출부마다 리터럴을 직접 박아 넣어 SpotBugs
 * SQL_INJECTION_JDBC 오탐 자체가 발생하지 않도록 설계했다(M2-T2에서 GRANT 훅에 겪은 것과 동일한 오탐을 여기서는 애초에 피함).
 */
public final class RootFixtureCleaner {

    private RootFixtureCleaner() {}

    /** {@code backfill_status} 테이블 전체를 root 커넥션으로 비운다. */
    public static void deleteAllBackfillStatus(String jdbcUrl) throws SQLException {
        try (Connection root = SharedMySqlContainer.rootDataSourceFor(jdbcUrl).getConnection();
                Statement statement = root.createStatement()) {
            statement.execute("DELETE FROM backfill_status");
        }
    }

    /** {@code stocks} 테이블 전체를 root 커넥션으로 비운다. */
    public static void deleteAllStocks(String jdbcUrl) throws SQLException {
        try (Connection root = SharedMySqlContainer.rootDataSourceFor(jdbcUrl).getConnection();
                Statement statement = root.createStatement()) {
            statement.execute("DELETE FROM stocks");
        }
    }

    /** {@code daily_ohlcv} 테이블 전체를 root 커넥션으로 비운다. */
    public static void deleteAllDailyOhlcv(String jdbcUrl) throws SQLException {
        try (Connection root = SharedMySqlContainer.rootDataSourceFor(jdbcUrl).getConnection();
                Statement statement = root.createStatement()) {
            statement.execute("DELETE FROM daily_ohlcv");
        }
    }
}
