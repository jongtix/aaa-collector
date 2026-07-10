package com.aaa.collector.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.support.SharedMySqlContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link RedisHealthConfig} 자동 구성 순서 회귀 통합 테스트 (aaa-infra#89).
 *
 * <p><b>재현하는 결함</b>: 사용자 정의 {@code @Configuration}은 Spring Boot auto-configuration보다 <em>먼저</em>
 * 처리된다. {@code RedisConnectionFactory}를 만드는 {@code RedisAutoConfiguration}이 아직 실행되지 않은 시점에
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)}가 평가되므로 조건이 항상 거짓이 되어 {@code
 * "redisHealthIndicator"} 빈이 등록되지 않는다. {@code application.yml}의 {@code
 * management.health.redis.enabled=false}로 Spring 기본 Redis 인디케이터도 꺼져 있어, 결함 상태에서 {@code
 * /actuator/health}는 Redis liveness 정보를 전혀 담지 못한다.
 *
 * <p><b>검증 방식</b>: 실 Redis Testcontainer(+실 MySQL 컨텍스트)에서 {@code RedisAutoConfiguration}이 활성인 전체
 * {@code @SpringBootTest} 컨텍스트를 띄우고, {@code "redisHealthIndicator"} 빈이 등록되며 PING 기반 liveness가 UP을
 * 반환함을 단언한다. 결함 코드(@Configuration + @ConditionalOnBean)에서는 {@code containsBean}이 거짓이 되어 RED로 실패하고,
 * {@code @AutoConfiguration(after = RedisAutoConfiguration.class)}로 수정하면 GREEN이 된다.
 *
 * <p><b>프로파일 선택</b>: {@code db-integration} 프로파일은 {@code RedisAutoConfiguration}을 명시 제외하므로 이 회귀를
 * 재현할 수 없다 — {@code test} 프로파일 단독으로 auto-configuration을 살려 둔다. {@code test} 단독은 {@code
 * spring.data.redis.{username,password}}의 {@code ${REDIS_APPUSER_*}} 플레이스홀더를 해소하지 못하므로,
 * {@code @ServiceConnection} 대신 {@code @DynamicPropertySource}로 host/port/username/password를 명시
 * 오버라이드한다(전용 {@code GenericContainer}는 {@code @ServiceConnection}으로 이 플레이스홀더들이 안정적으로 해소되지 않는다).
 *
 * <p><b>컨테이너 생명주기 / 가드 준수</b>: 이 클래스는 공유 MySQL 싱글턴({@code SharedMySqlContainer.MYSQL})을 참조하므로,
 * {@link com.aaa.collector.arch.SharedContainerGuardTest} 규칙 A(공유 컨테이너 참조 클래스는 {@code @Container}
 * 필드를 두지 않는다 — {@code @Container}의 클래스별 {@code @AfterAll} stop() 계약이 공유 컨테이너를 조기 종료시키기 때문)를 지키기 위해,
 * 전용 Redis 컨테이너도 {@code @Container} 대신 static 블록 start()로 기동한다(SharedMySqlContainer와 동일한 싱글턴 컨테이너
 * 패턴, 컨테이너는 JVM 종료 시 Testcontainers Ryuk이 회수). 규칙 B(공유 컨테이너 참조 클래스는 격리 전략 필요)에 대해서는 빈 등록만 조회하는 읽기
 * 전용 게이트이므로 {@code @Transactional} 없이 {@code
 * SharedContainerGuardTest.TRANSACTIONAL_EXEMPT_ALLOWLIST}에 등재된다(DatabaseMigrationIntegrationTest와
 * 동일 사유). 클래스 레벨 {@code @Tag("integration")}는 {@code integrationTest} 태스크(CI check)에서만 실행되도록 하기
 * 위함이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DisplayName("RedisHealthConfig 자동 구성 순서 통합 테스트 (실 Redis + 실 MySQL)")
class RedisHealthAutoConfigurationIntegrationTest {

    // 공유 MySQL 싱글턴(SharedMySqlContainer.MYSQL) 참조 — @Container 금지, @ServiceConnection만
    // (SharedContainerGuardTest 규칙 A). 생명주기는 SharedMySqlContainer의 static 블록이 단독 소유한다.
    @ServiceConnection static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    // 전용 Redis 컨테이너. @ServiceConnection이 아닌 @DynamicPropertySource로 접속 정보를 명시 주입한다
    // (클래스 Javadoc "프로파일 선택" 참고). @Container를 붙이지 않고 static 블록으로 기동하는 이유는 클래스 Javadoc
    // "컨테이너 생명주기 / 가드 준수" 참고(SharedContainerGuardTest 규칙 A 준수).
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Autowired private ApplicationContext applicationContext;

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // redis:8-alpine 기본(무인증) — application.yml의 ${REDIS_APPUSER_*} 플레이스홀더를 빈 값으로 오버라이드해
        // Lettuce가 AUTH를 보내지 않도록 한다(빈 password → RedisPassword.none()).
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
    }

    @Test
    @DisplayName("실 Redis 컨텍스트에서 redisHealthIndicator 빈이 등록되고 헬스가 UP을 반환한다")
    void redisHealthIndicator_isRegistered_andReportsUp() {
        // Assert — 결함(@Configuration + @ConditionalOnBean)에서는 이 단언이 거짓으로 RED 실패한다.
        assertThat(applicationContext.containsBean("redisHealthIndicator")).isTrue();

        RedisPingHealthIndicator indicator =
                applicationContext.getBean("redisHealthIndicator", RedisPingHealthIndicator.class);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }
}
