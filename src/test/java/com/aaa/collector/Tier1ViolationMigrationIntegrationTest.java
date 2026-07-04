package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// SPEC-COLLECTOR-DBGRANT-003 M1-T2 (REQ-DBGRANT3-010, AC-2, spec §3 D7):
// aaa-infra 이슈 #68 사고(원 V33의 Tier-1 corporate_events UPDATE가 로컬 테스트는 통과하고
// NAS 프로덕션 배포에서야 SQL 1142로 실패)의 로컬 재현을 영구 회귀 테스트로 고정한다.
//
// 테스트 전용 위반 마이그레이션(db/migration-tier1-violation/V9999__forbidden_update.sql —
// 기본 Flyway location 밖이라 정상 스위트에 미포함)을 명시 location으로 추가한 별도 Flyway
// 인스턴스를 UPDATE 권한이 없는 flyway 계정으로 migrate하여 MySQL Error 1142를 단언한다.
// 미러 init 스크립트가 훼손되어 flyway 계정에 UPDATE가 생기면(거짓 안전) migrate가 성공해
// 이 테스트가 RED가 된다 — "환경이 위반을 잡는 능력" 자체를 고정하는 테스트다.
//
// Spring 컨텍스트 불요(앱 Flyway 자동 구성 경로와 완전 분리) — 순수 JUnit + Testcontainers.
@Testcontainers
@Tag("integration")
@DisplayName("AC-2 — Tier-1 UPDATE 위반 마이그레이션 1142 RED 회귀 테스트 (#68 로컬 재현)")
class Tier1ViolationMigrationIntegrationTest {

    // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는 SharedMySqlContainer의 static 블록이
    // 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    /** 기본 마이그레이션 location — 앱 Flyway 자동 구성(application.yml)과 동일 값. */
    private static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

    /** 위반 마이그레이션 전용 location — 이 테스트만 명시 로드한다(정상 스위트 미포함, AC-2). */
    private static final String VIOLATION_MIGRATION_LOCATION =
            "classpath:db/migration-tier1-violation";

    /** MySQL Error 1142: 계정에 없는 권한의 명령 시도(UPDATE command denied). */
    private static final int MYSQL_ERROR_COMMAND_DENIED = 1142;

    /**
     * V9999 시도가 flyway_schema_history에 남긴 행(실패 기록 또는 — 미러 훼손 시 — 성공 기록)을 제거해 공유 컨테이너의 스키마 히스토리를 정상
     * 상태(V1~V33만 적용)로 복원한다. 잔류 실패 행은 이후 앱 Flyway 자동 구성 경로의 validate를 깨뜨리므로 (M2에서 컨테이너가 전 스위트 공유로
     * 전환될 때 실제 오염 경로가 된다) 결과와 무관하게 항상 정리한다. flyway 계정은 DELETE 권한을 보유한다(ADR-016 — DDL 전용 계정의 권한 집합에
     * DELETE 포함).
     */
    @AfterEach
    void deleteViolationHistoryRow() throws SQLException {
        try (Connection connection = SharedMySqlContainer.flywayDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM flyway_schema_history WHERE version = '9999'");
        }
    }

    @Test
    @DisplayName("flyway 계정으로 Tier-1 UPDATE 마이그레이션을 실행하면 MySQL Error 1142로 실패한다")
    void tier1UpdateMigrationFailsWithError1142UnderRestrictedFlywayAccount() {
        // Arrange: 기본 location(V1~V33) + 위반 전용 location(V9999)을 함께 로드하는 Flyway를
        //          UPDATE 권한이 없는 flyway 계정 DataSource로 구성한다
        Flyway violationFlyway =
                Flyway.configure()
                        .dataSource(SharedMySqlContainer.flywayDataSource())
                        .locations(DEFAULT_MIGRATION_LOCATION, VIOLATION_MIGRATION_LOCATION)
                        .load();

        // Act: V1~V33은 성공하고(AC-1과 동일 경로) V9999의 Tier-1 UPDATE에서 실패해야 한다
        Throwable thrown = catchThrowable(violationFlyway::migrate);

        // Assert: 미러 훼손으로 flyway 계정에 UPDATE가 부여되면 migrate가 성공해 여기서 RED가 된다
        assertThat(thrown)
                .as("Tier-1 UPDATE 마이그레이션은 flyway 계정에서 반드시 실패해야 한다(#68 재현)")
                .isInstanceOf(FlywayException.class);
        SQLException sqlCause = firstSqlException(thrown);
        // SQLException은 Iterable<Throwable>이기도 해 assertThat 오버로드가 모호하므로 Throwable로 명시
        assertThat((Throwable) sqlCause)
                .as("실패 원인 체인에 MySQL 권한 오류(SQLException)가 있어야 한다")
                .isNotNull();
        assertThat(sqlCause.getErrorCode()).isEqualTo(MYSQL_ERROR_COMMAND_DENIED);
        assertThat(sqlCause.getMessage()).contains("UPDATE command denied to user 'flyway'");
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
