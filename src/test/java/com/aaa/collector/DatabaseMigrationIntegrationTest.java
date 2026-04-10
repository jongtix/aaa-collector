package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 참고: deploy.yml의 마이그레이션 버전 파싱 스크립트(V*.sql → 최대 버전 번호 추출)는
// Flyway의 validate-migration-naming: true 설정에 의해 비표준 파일명이 빌드 시점에
// 차단되므로, 이 테스트가 스크립트 파싱 안전망 역할을 겸한다.
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("Flyway 마이그레이션 + JPA ddl-auto=validate 통합 테스트")
class DatabaseMigrationIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private Flyway flyway;

    @Test
    @DisplayName("모든 마이그레이션이 적용되고 미적용 마이그레이션이 없다")
    void allMigrationsApplied() {
        // Arrange: ApplicationContext 로드 성공 시점에 Flyway 마이그레이션과
        //          Hibernate ddl-auto=validate는 이미 통과한 상태
        // Act & Assert
        assertThat(flyway.info().pending()).isEmpty();
    }
}
