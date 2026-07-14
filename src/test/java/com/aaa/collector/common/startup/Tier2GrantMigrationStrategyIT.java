package com.aaa.collector.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// SPEC-COLLECTOR-DBGRANT-003 M2-T2: grant 순서 훅(Tier2GrantMigrationStrategy)이 Flyway migrate
// 직후 root 커넥션으로 DbGrantVerifier.TIER2_TABLES 5개 테이블에 collector 계정 UPDATE GRANT를
// 적용하는지 검증한다(프로덕션 "Flyway 선행 -> root grants 후행" 절차의 미러, REQ-DBGRANT3-004/-006).
//
// M2-T1 격리 분류: 스키마/권한을 조회만 할 뿐(SHOW GRANTS) 비즈니스 데이터 행을 생성하지 않으므로
// 격리 전략(@Transactional/root fixture)이 불필요하다(읽기 전용 게이트, DatabaseMigrationIntegrationTest와 동일).
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("Tier2GrantMigrationStrategy — Flyway migrate 이후 Tier-2 UPDATE GRANT 순서 훅")
@Tag("integration")
class Tier2GrantMigrationStrategyIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조,
    // DatabaseMigrationIntegrationTest와 동일 이유)
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;

    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;

    @Test
    @DisplayName("AC — SHOW GRANTS FOR 'collector'@'%'에 TIER2_TABLES 5개 UPDATE가 모두 존재한다")
    void collectorAccountHasUpdateOnAllTier2Tables() throws SQLException {
        // Act: collector 계정 자신의 GRANT를 직접 연결로 조회
        List<String> grants = SharedMySqlContainer.showCollectorGrants();

        // Assert: DbGrantVerifier.TIER2_TABLES(5개) 전부에 대해 UPDATE 부여 행이 존재해야 한다
        for (String table : DbGrantVerifier.TIER2_TABLES) {
            assertThat(grants)
                    .as("table=%s", table)
                    .anyMatch(
                            grant -> grant.contains("UPDATE") && grant.contains("`" + table + "`"));
        }
    }
}
