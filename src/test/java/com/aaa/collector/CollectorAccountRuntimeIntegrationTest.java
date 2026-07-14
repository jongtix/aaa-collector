package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// SPEC-COLLECTOR-DBGRANT-003 M2-T4 (REQ-DBGRANT3-003/-012/-030, AC-5/AC-6/AC-7, spec §3 D7):
// CollectorAccountDataSourceSwitcher가 앱 datasource를 collector 계정으로 전환했음을 확인하고
// (AC-5), Tier-1 테이블(daily_ohlcv) UPDATE를 정규식 가드가 탐지하지 못하는 형태(JdbcTemplate
// 평문 UPDATE)로 시도해 MySQL Error 1142로 실패함을 영구 회귀 테스트로 고정한다(AC-7 —
// "환경이 실제로 위반을 잡는 능력" 자체를 고정, DBGRANT-002/M1-T2와 동일 철학). Tier-2
// 테이블(backfill_status)의 정당한 UPDATE 경로는 grant 훅(Tier2GrantMigrationStrategy) 적용
// 이후 collector 계정에서도 계속 green이어야 한다(REQ-030).
//
// 클래스 레벨 @Transactional — Tier-2 GREEN 테스트가 실제 INSERT+UPDATE 행을 남기므로 롤백
// 기반 격리가 필요하다(M2-T1 REQ-015 기본 전략). Tier-1 RED 테스트는 커밋 전 권한 오류로
// 거부되므로 롤백 대상 행 자체가 없다.
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("collector 계정 런타임 권한 통합 테스트 (AC-5, AC-6, AC-7)")
@Tag("integration")
class CollectorAccountRuntimeIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조)
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** MySQL Error 1142: 계정에 없는 권한의 명령 시도(UPDATE command denied). */
    private static final int MYSQL_ERROR_COMMAND_DENIED = 1142;

    @Test
    @DisplayName("AC-5 — 앱 datasource가 collector 계정으로 연결된다")
    void appDatasourceConnectsAsCollectorAccount() {
        String currentUser = jdbcTemplate.queryForObject("SELECT CURRENT_USER()", String.class);

        assertThat(currentUser).startsWith("collector@");
    }

    @Test
    @DisplayName("AC-7 — Tier-1 테이블(daily_ohlcv) UPDATE는 JdbcTemplate 평문 SQL로도 1142로 실패한다")
    void tier1TableUpdateFailsWithError1142ViaJdbcTemplate() {
        // Act — 정규식 가드(ON DUPLICATE KEY UPDATE 리터럴 매칭)가 탐지 못하는 평문 UPDATE 형태
        Throwable thrown =
                catchThrowable(() -> jdbcTemplate.update("UPDATE daily_ohlcv SET tamt = tamt"));

        // Assert — collector 계정에 daily_ohlcv UPDATE 권한이 없어 반드시 실패해야 한다
        assertThat(thrown)
                .as("Tier-1 UPDATE는 collector 계정에서 반드시 실패해야 한다(ADR-026 불변성)")
                .isInstanceOf(DataAccessException.class);
        SQLException sqlCause = firstSqlException(thrown);
        assertThat((Throwable) sqlCause)
                .as("실패 원인 체인에 MySQL 권한 오류(SQLException)가 있어야 한다")
                .isNotNull();
        assertThat(sqlCause.getErrorCode()).isEqualTo(MYSQL_ERROR_COMMAND_DENIED);
        assertThat(sqlCause.getMessage()).contains("UPDATE command denied to user 'collector'");
    }

    @Test
    @DisplayName(
            "REQ-030 — Tier-2 테이블(backfill_status) UPDATE는 grant 훅 적용 후 collector 계정에서 정상 동작한다")
    void tier2TableUpdateSucceedsAfterGrantHook() {
        // Arrange
        jdbcTemplate.update(
                "INSERT INTO backfill_status "
                        + "(target_type, target_code, data_table, status, created_at, updated_at) "
                        + "VALUES ('STOCK', 'M2T4-PROBE', 'daily_ohlcv', 'PENDING', NOW(), NOW())");

        // Act & Assert — Tier2GrantMigrationStrategy가 적용한 UPDATE GRANT 덕분에 성공해야 한다
        assertThatCode(
                        () ->
                                jdbcTemplate.update(
                                        "UPDATE backfill_status SET status = 'IN_PROGRESS' "
                                                + "WHERE target_code = 'M2T4-PROBE'"))
                .as("Tier-2 테이블 UPDATE는 grant 훅 적용 후 collector 계정에서 성공해야 한다(ADR-026 결정 2)")
                .doesNotThrowAnyException();
    }

    /** 원인 체인을 따라가 최초의 {@link SQLException}을 반환한다(없으면 null). */
    private static SQLException firstSqlException(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }
}
