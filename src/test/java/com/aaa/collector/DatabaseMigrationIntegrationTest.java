package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.SQLException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 참고: deploy.yml의 마이그레이션 버전 파싱 스크립트(V*.sql → 최대 버전 번호 추출)는
// Flyway의 validate-migration-naming: true 설정에 의해 비표준 파일명이 빌드 시점에
// 차단되므로, 이 테스트가 스크립트 파싱 안전망 역할을 겸한다.
//
// SPEC-COLLECTOR-DBGRANT-003 M1-T1: 공유 컨테이너 지원 클래스(SharedMySqlContainer)의 MYSQL
// 인스턴스를 합성(상속 아님)으로 재사용하여, 프로덕션 계정 분리(ADR-026)를 재현하는 계정 미러가
// 주입된 컨테이너를 사용한다. Flyway는 application-test.yml에 지정된 제한된 flyway 계정
// (UPDATE 없음)으로 실행된다.
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("Flyway 마이그레이션 + JPA ddl-auto=validate 통합 테스트")
@Tag("integration")
class DatabaseMigrationIntegrationTest {

    @Container @ServiceConnection static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("모든 마이그레이션이 적용되고 미적용 마이그레이션이 없다")
    void allMigrationsApplied() {
        // Arrange: ApplicationContext 로드 성공 시점에 Flyway 마이그레이션(flyway 계정)과
        //          Hibernate ddl-auto=validate는 이미 통과한 상태
        // Act & Assert
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("AC-15 — V28 마이그레이션 이후 ranking_snapshots 테이블 부재 단언")
    void v25_rankingSnapshotsTableDropped() {
        // Act & Assert: information_schema 직접 조회 — 테이블 존재하면 count=1, 없으면 0
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema = DATABASE() "
                                + "AND table_name = 'ranking_snapshots'",
                        Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("AC-3 — flyway 계정에 UPDATE 권한이 없다 (SHOW GRANTS FOR 'flyway'@'%')")
    void flywayAccountHasNoUpdatePrivilege() throws SQLException {
        // Act: 공유 지원 클래스의 헬퍼로 flyway 계정 자신의 GRANT를 조회한다
        List<String> grants = SharedMySqlContainer.showFlywayGrants();
        // Assert
        assertThat(grants).isNotEmpty();
        assertThat(grants).noneMatch(grant -> grant.contains("UPDATE"));
    }
}
