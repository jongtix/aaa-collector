package com.aaa.collector.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway 기동 시 자동 적용 회귀 가드 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-003·017, AC-BOOT4-003).
 *
 * <p>Boot 4는 Flyway 자동구성을 별도 스타터({@code spring-boot-starter-flyway})로 분리했다. 스타터가 빠지면 {@code
 * flyway-core}가 클래스패스에 있어도 마이그레이션이 조용히 실행되지 않으므로, 컨텍스트 기동만으로 {@code flyway_schema_history}의 최신 성공
 * 버전이 {@code 49}가 되고 {@code ddl-auto=validate}를 통과함을 고정한다. 이력 테이블은 Flyway API가 아니라 SQL로 읽어 Flyway
 * 메이저 버전 차이와 무관하게 판정한다.
 *
 * <p>컨텍스트 구성(프로파일·목 빈)은 {@code DatabaseMigrationIntegrationTest}와 같게 두어 컨텍스트 캐시를 공유한다. 조회만 하지만 공유
 * 컨테이너 규약에 따라 클래스 레벨 {@code @Transactional}을 둔다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@Tag("integration")
@DisplayName("Flyway 기동 시 자동 적용 회귀 가드 — 최신 성공 버전 49 + validate")
class FlywayAppliedOnStartupIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조)
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Flyway flyway;
    @Autowired private Environment environment;

    @Test
    @DisplayName("기동만으로 flyway_schema_history 최신 성공 버전이 49다")
    void latestSuccessfulVersionIs49() {
        String latest =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM flyway_schema_history "
                                + "WHERE success = 1 AND version IS NOT NULL "
                                + "ORDER BY installed_rank DESC LIMIT 1",
                        String.class);

        assertThat(latest).isEqualTo("49");
    }

    @Test
    @DisplayName("실패한 마이그레이션 이력이 없다")
    void noFailedMigrations() {
        Integer failed =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0",
                        Integer.class);

        assertThat(failed).isZero();
    }

    @Test
    @DisplayName("Flyway validate가 통과하고 JPA는 ddl-auto=validate로 기동했다")
    void validatePasses() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
}
