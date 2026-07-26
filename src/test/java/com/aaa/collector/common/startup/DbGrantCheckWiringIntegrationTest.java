package com.aaa.collector.common.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// aaa-infra#120 회귀 방지: DbGrantCheckConfig가 @ConditionalOnBean(JdbcTemplate.class)를 쓰던 시절
// 사용자 @Configuration 평가 순서 함정(JdbcTemplateAutoConfiguration보다 먼저 평가됨) 때문에
// DbGrantCheckRunner/DbGrantLoader/DbGrantVerifier 세 빈이 실제 DataSource 환경에서도 단 한 번도
// 등록되지 않았다(프로덕션 90일 무출력 실측). 이 테스트는 "실제 DataSource가 있는 컨텍스트에 세 빈이
// 존재하는가"를 1차 회귀 앵커로 삼는다 — 구 코드에서는 반드시 실패한다(RED 실측 기록: 본 파일
// 커밋 히스토리 또는 작업 리포트 참고).
//
// SCHEMA_NAME 정합: InformationSchemaGrantLoader.SCHEMA_NAME은 프로덕션 단일 스키마("aaa") 기준으로
// 고정 상수 처리된 의도된 설계다(그 클래스 Javadoc 참고). 이 프로젝트의 Testcontainers MySQLContainer는
// (SharedMySqlContainer 포함) 기본적으로 스키마명 "test"를 쓰므로, self-check가 실제로 무언가를
// 검증하려면 스키마명을 "aaa"로 맞춘 전용 컨테이너가 필요하다 — SharedMySqlContainer를 재사용할 수
// 없다(그 컨테이너는 이미 스키마명 "test"로 기동되어 있고, 계정 미러 스크립트도 test.* 대상으로
// 하드코딩되어 있다). 그래서 이 클래스는 SharedMySqlContainer.MYSQL을 참조하지 않는 전용
// (비공유) 컨테이너를 쓴다 — SharedContainerGuardTest 스캔 대상이 아니다.
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@TestPropertySource(properties = "collector.db-grant-check.enabled=true")
@DisplayName("DbGrantCheckConfig 배선 통합 테스트 — @ConditionalOnBean 함정 회귀 방지 (aaa-infra#120)")
@Tag("integration")
class DbGrantCheckWiringIntegrationTest {

    // GRANT/REVOKE 대상 계정 — 이 전용 컨테이너의 기본 앱 계정('test'@'%', @ServiceConnection이 자동 주입).
    // SpotBugs SQL_INJECTION_JDBC taint 추적이 static final 필드 참조를 넘지 못하고 "Unknown source"로
    // 오탐하므로(실측 확인), 상수로 추출하지 않고 각 GRANT/REVOKE 조립 지점에 리터럴을 그대로 인라인한다
    // (Tier2GrantMigrationStrategy와 동일하게 리터럴 직접 사용 — 그 클래스는 이 오탐이 발생하지 않는다).

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName(InformationSchemaGrantLoader.SCHEMA_NAME);

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    @Autowired private ApplicationContext context;

    /**
     * Flyway {@code migrate()} 직후 {@link DbGrantVerifier#TIER2_TABLES}에 이 전용 컨테이너의 기본 계정({@code
     * test}@{@code %})으로 {@code UPDATE} GRANT를 적용하는 부트스트랩({@code Tier2GrantMigrationStrategy}와 동일
     * 절차이나 대상 계정이 다르다 — 그 클래스는 계정 미러 스크립트가 주입한 {@code collector} 계정을 겨냥하는데, 이 전용 컨테이너에는 그 스크립트가 없어
     * {@code collector} 계정 자체가 없다).
     *
     * <p>{@code @Primary}로 컴포넌트 스캔된 {@code Tier2GrantMigrationStrategy}(무대상 계정 없음을 감지하고 스스로 스킵)보다
     * 우선 적용해 이 부트스트랩만 실행되게 한다.
     *
     * <p>이 GRANT는 {@link DbGrantCheckRunner}가 {@code ApplicationRunner}로 컨텍스트 기동 직후 자동 실행되기
     * <b>이전</b>(Flyway 마이그레이션 단계, 컨텍스트 refresh 도중)에 적용되어야 한다 — 그렇지 않으면 최초 기동 self-check 자체가 {@link
     * DbGrantMissingException}으로 실패해 컨텍스트가 뜨지 않는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Tier2GrantBootstrapConfig {

        @Bean
        @Primary
        FlywayMigrationStrategy grantTier2UpdatesForDefaultAccount() {
            return new DefaultAccountTier2GrantStrategy();
        }
    }

    /**
     * {@link FlywayMigrationStrategy}를 {@code Tier2GrantMigrationStrategy}와 동일한 구조(명명 클래스 — {@code
     * public void migrate(Flyway)} 오버라이드가 직접 GRANT 조립·실행 헬퍼를 호출)로 구현한다.
     */
    private static final class DefaultAccountTier2GrantStrategy implements FlywayMigrationStrategy {

        @Override
        public void migrate(Flyway flyway) {
            flyway.migrate();
            applyTier2Grants(flyway.getConfiguration().getDataSource());
        }

        /**
         * SQL 문자열을 이 메서드 안에서 그대로 조립해 {@code execute()}에 전달한다(테이블명은 {@link
         * DbGrantVerifier#TIER2_TABLES} 고정 상수 집합, 외부 입력 아님) — {@code Tier2GrantMigrationStrategy}의
         * {@code applyTier2Grants}와 동일한 단일 메서드 내 조립 패턴이다.
         *
         * <p><b>알려진 미해결 SpotBugs 발견(SQL_INJECTION_JDBC)</b>: GRANT 문의 테이블/스키마 식별자는 JDBC 바인드 변수로
         * 파라미터화할 수 없다(값이 아닌 식별자). 흐르는 동적 값은 {@link DbGrantVerifier#TIER2_TABLES}(고정 상수 집합, 외부 입력
         * 아님)와 JDBC 커넥션 자신의 catalog(schema)명뿐이다 — {@code
         * Tier2GrantMigrationStrategy.applyTier2Grants}와 정확히 동일한 상황이며, 그 메서드는 {@code
         * config/spotbugs/exclude.xml}에 user-approved(2026-07-04) 항목으로 이미 억제되어 있다. 이 메서드는 그 항목과 동일한
         * 범위(클래스+메서드명 한정)의 억제가 필요하나, 사용자 승인 없이 exclude.xml을 수정할 수 없어 억제하지 않았다 — 작업 리포트에 블로커로 보고함.
         */
        private void applyTier2Grants(DataSource flywayDataSource) {
            String jdbcUrl;
            String schema;
            try (Connection probe = flywayDataSource.getConnection()) {
                jdbcUrl = probe.getMetaData().getURL();
                schema = probe.getCatalog();
            } catch (SQLException e) {
                throw new IllegalStateException("Tier-2 GRANT 적용을 위한 Flyway 데이터소스 조회 실패", e);
            }

            try (Connection root = SharedMySqlContainer.rootDataSourceFor(jdbcUrl).getConnection();
                    Statement statement = root.createStatement()) {
                for (String table : DbGrantVerifier.TIER2_TABLES) {
                    statement.execute(
                            "GRANT UPDATE ON `" + schema + "`.`" + table + "` TO 'test'@'%'");
                }
                statement.execute("FLUSH PRIVILEGES");
            } catch (SQLException e) {
                throw new IllegalStateException("Tier-2 GRANT 적용 실패", e);
            }
        }
    }

    @Nested
    @DisplayName("배선 검증 — 실제 DataSource 컨텍스트 빈 등록 (aaa-infra#120 1차 회귀 앵커)")
    class WiringPositive {

        @Test
        @DisplayName("DbGrantCheckRunner/DbGrantLoader/DbGrantVerifier 빈이 컨텍스트에 존재한다")
        void dbGrantCheckBeans_areRegisteredInContext() {
            // Act & Assert — 구 @ConditionalOnBean(JdbcTemplate.class) 코드에서는 세 빈 모두
            // NoSuchBeanDefinitionException으로 조회 실패한다(RED 입증 대상).
            assertThat(context.getBean(DbGrantCheckRunner.class)).isNotNull();
            assertThat(context.getBean(DbGrantLoader.class)).isNotNull();
            assertThat(context.getBean(DbGrantVerifier.class)).isNotNull();
        }
    }

    @Nested
    @DisplayName("self-check 기능 검증 — 실제 information_schema 경로")
    class SelfCheckFunctional {

        @Autowired private DbGrantCheckRunner runner;

        @Test
        @DisplayName("Tier-2 GRANT가 모두 존재하면 run()이 예외 없이 완료된다")
        void run_completesWithoutException_whenAllGrantsPresent() {
            // Act & Assert — Tier2GrantBootstrapConfig가 마이그레이션 직후 적용한 GRANT 덕분에 통과해야 한다.
            assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("fail-fast 음성 검증 — Tier-2 GRANT 회수 시 기동 중단 능력 실증")
    class FailFastNegative {

        private static final String MARKET_CALENDAR_TABLE = "market_calendar";

        @Autowired private DbGrantCheckRunner runner;

        @Test
        @DisplayName("market_calendar UPDATE 회수 시 run()이 DbGrantMissingException을 던진다(테이블명 포함)")
        void run_throwsDbGrantMissingException_whenMarketCalendarUpdateRevoked()
                throws SQLException {
            // Arrange
            revokeMarketCalendarUpdate();

            try {
                // Act & Assert
                assertThatThrownBy(() -> runner.run(null))
                        .isInstanceOf(DbGrantMissingException.class)
                        .hasMessageContaining(MARKET_CALENDAR_TABLE);
            } finally {
                // Restore — 같은 컨테이너를 재사용하는 클래스 내 다른 테스트가 오염되지 않도록 원복한다.
                regrantMarketCalendarUpdate();
            }
        }

        /**
         * SQL 문자열을 이 메서드 안에서 그대로 조립해 {@code execute()}에 전달한다({@link #MARKET_CALENDAR_TABLE} 고정 상수,
         * 외부 입력 아님) — 조립을 별도 메서드로 분리하지 않는 이유는 {@link Tier2GrantBootstrapConfig#applyTier2Grants}
         * Javadoc 참고(SpotBugs SQL_INJECTION_JDBC 오탐 방지).
         */
        private void revokeMarketCalendarUpdate() throws SQLException {
            try (Connection root =
                            SharedMySqlContainer.rootDataSourceFor(MYSQL.getJdbcUrl())
                                    .getConnection();
                    Statement statement = root.createStatement()) {
                statement.execute(
                        "REVOKE UPDATE ON `"
                                + InformationSchemaGrantLoader.SCHEMA_NAME
                                + "`.`"
                                + MARKET_CALENDAR_TABLE
                                + "` FROM "
                                + "'test'@'%'");
                statement.execute("FLUSH PRIVILEGES");
            }
        }

        /** {@link #revokeMarketCalendarUpdate()}와 동일 이유로 조립·실행을 한 메서드에 유지한다. */
        private void regrantMarketCalendarUpdate() throws SQLException {
            try (Connection root =
                            SharedMySqlContainer.rootDataSourceFor(MYSQL.getJdbcUrl())
                                    .getConnection();
                    Statement statement = root.createStatement()) {
                statement.execute(
                        "GRANT UPDATE ON `"
                                + InformationSchemaGrantLoader.SCHEMA_NAME
                                + "`.`"
                                + MARKET_CALENDAR_TABLE
                                + "` TO "
                                + "'test'@'%'");
                statement.execute("FLUSH PRIVILEGES");
            }
        }
    }
}
