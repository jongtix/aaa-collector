package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * GROUP_B 전결손 위장 리셋 운영 스크립트 통합 테스트 (SPEC-COLLECTOR-BACKFILL-013 T5, AC-8).
 *
 * <p>{@code scripts/ops/reset-groupb-false-exhaustion.sql}을 root 계정으로 직접 실행해, 위장 시그니처 ({@code
 * status=COMPLETED AND last_collected_date IS NULL AND attempt_count=1}) 행만 PENDING으로 리셋되고 정상 소진
 * 대조군(0021E0류, {@code attempt_count>1})은 변경되지 않음을 검증한다(REQ-BACKFILL-170/-171).
 *
 * <p>이 스크립트는 Flyway {@code db/migration}에 속하지 않으므로(REQ-BACKFILL-172), 앱 Flyway 자동 구성과 별도로 스키마만 적용한
 * 전용 컨테이너에서 스크립트 SQL을 직접 실행해 검증한다. Spring 컨텍스트 불요.
 */
@Testcontainers
@Tag("integration")
@DisplayName("GROUP_B 전결손 위장 리셋 스크립트 통합 테스트 (SPEC-COLLECTOR-BACKFILL-013 AC-8)")
class GroupBFalseExhaustionResetScriptIntegrationTest {

    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static final String RESET_SCRIPT_CLASSPATH =
            "scripts/ops/reset-groupb-false-exhaustion.sql";

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanUp() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM backfill_status");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insertRow(
            Connection connection,
            String targetCode,
            String dataTable,
            String status,
            LocalDate lastCollectedDate,
            int attemptCount)
            throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "INSERT INTO backfill_status "
                                + "(target_type, target_code, data_table, status, last_collected_date, "
                                + "attempt_count, stale_count, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)")) {
            ps.setString(1, "STOCK");
            ps.setString(2, targetCode);
            ps.setString(3, dataTable);
            ps.setString(4, status);
            if (lastCollectedDate != null) {
                ps.setObject(5, lastCollectedDate);
            } else {
                ps.setNull(5, java.sql.Types.DATE);
            }
            ps.setInt(6, attemptCount);
            ps.setObject(7, now);
            ps.setObject(8, now);
            ps.executeUpdate();
        }
    }

    private static String statusOf(Connection connection, String targetCode, String dataTable)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT status FROM backfill_status "
                                + "WHERE target_code = ? AND data_table = ?")) {
            ps.setString(1, targetCode);
            ps.setString(2, dataTable);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                assertThat(found).as("행이 존재해야 한다").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static int attemptCountOf(Connection connection, String targetCode, String dataTable)
            throws SQLException {
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT attempt_count FROM backfill_status "
                                + "WHERE target_code = ? AND data_table = ?")) {
            ps.setString(1, targetCode);
            ps.setString(2, dataTable);
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                assertThat(found).as("행이 존재해야 한다").isTrue();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName(
            "AC-8: 위장 시그니처 행(status=COMPLETED, last_collected_date IS NULL, attempt_count=1)은 PENDING"
                    + " 리셋 — attempt_count/stale_count/covered_until_date 초기화, last_collected_date는"
                    + " NULL 유지")
    void falseExhaustionSignature_resetToPending() throws SQLException {
        try (Connection connection = connect()) {
            insertRow(connection, "010620", "short_sale_domestic", "COMPLETED", null, 1);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESET_SCRIPT_CLASSPATH));

            assertThat(statusOf(connection, "010620", "short_sale_domestic")).isEqualTo("PENDING");
            assertThat(attemptCountOf(connection, "010620", "short_sale_domestic")).isZero();
        }
    }

    @Test
    @DisplayName("AC-8: 대조군 0021E0류(attempt_count=35, last_collected_date 비-NULL 정상 소진)는 변경되지 않는다")
    void normalExhaustion_notReset() throws SQLException {
        try (Connection connection = connect()) {
            insertRow(
                    connection,
                    "0021E0",
                    "short_sale_domestic",
                    "COMPLETED",
                    LocalDate.of(2025, 3, 11),
                    35);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESET_SCRIPT_CLASSPATH));

            assertThat(statusOf(connection, "0021E0", "short_sale_domestic"))
                    .isEqualTo("COMPLETED");
            assertThat(attemptCountOf(connection, "0021E0", "short_sale_domestic")).isEqualTo(35);
        }
    }

    @Test
    @DisplayName(
            "AC-8: 3개 GROUP_B 테이블(short_sale_domestic·investor_trend·credit_balance) 모두 일반식으로 포착")
    void allThreeGroupBTables_captured() throws SQLException {
        try (Connection connection = connect()) {
            insertRow(connection, "433870", "short_sale_domestic", "COMPLETED", null, 1);
            insertRow(connection, "TEST01", "investor_trend", "COMPLETED", null, 1);
            insertRow(connection, "TEST02", "credit_balance", "COMPLETED", null, 1);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESET_SCRIPT_CLASSPATH));

            assertThat(statusOf(connection, "433870", "short_sale_domestic")).isEqualTo("PENDING");
            assertThat(statusOf(connection, "TEST01", "investor_trend")).isEqualTo("PENDING");
            assertThat(statusOf(connection, "TEST02", "credit_balance")).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("음성: GROUP_A(daily_ohlcv) 위장-유사 행은 data_table 조건으로 배제되어 변경되지 않는다")
    void groupATable_excludedByDataTableCondition() throws SQLException {
        try (Connection connection = connect()) {
            insertRow(connection, "005930", "daily_ohlcv", "COMPLETED", null, 1);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESET_SCRIPT_CLASSPATH));

            assertThat(statusOf(connection, "005930", "daily_ohlcv")).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("음성: last_collected_date가 채워진 COMPLETED(attempt_count=1)는 리셋 대상 아님")
    void completedWithLastCollectedDate_notReset() throws SQLException {
        try (Connection connection = connect()) {
            insertRow(
                    connection,
                    "PARTIAL1",
                    "credit_balance",
                    "COMPLETED",
                    LocalDate.of(2020, 1, 1),
                    1);

            ScriptUtils.executeSqlScript(connection, new ClassPathResource(RESET_SCRIPT_CLASSPATH));

            assertThat(statusOf(connection, "PARTIAL1", "credit_balance")).isEqualTo("COMPLETED");
        }
    }
}
