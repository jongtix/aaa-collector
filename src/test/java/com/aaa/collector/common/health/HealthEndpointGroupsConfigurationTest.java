package com.aaa.collector.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code management.endpoint.health.group} liveness/readiness 분리 설정 검증.
 *
 * <p>Redis는 collector의 소프트 의존성(KIS 토큰 캐싱 + Streams 틱 발행)이므로, liveness 그룹은 Redis/DB에 의존하지 않는 {@code
 * livenessState}만 포함해야 한다. Redis 장애가 aggregate {@code /actuator/health}를 DOWN으로 만들어도 Docker
 * HEALTHCHECK({@code /actuator/health/liveness})는 영향받지 않아야 CD의 오탐 롤백을 방지한다.
 *
 * <p>전체 애플리케이션 컨텍스트({@code @SpringBootTest}) 대신 {@link ApplicationContextRunner}로 health
 * auto-configuration만 격리 로드한다 — {@link HealthEndpointGroup#isMember(String)}은 {@code
 * application.yml}의 include 목록 설정만 검사하므로 실제 DB/Redis 컨트리뷰터 빈 존재 여부와 무관하며, 여기서 검증하는 것은 오직 {@code
 * management.endpoint.health.group.*} 프로퍼티 바인딩 결과다.
 */
@DisplayName("Actuator health liveness/readiness 그룹 설정 테스트")
class HealthEndpointGroupsConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    HealthContributorAutoConfiguration.class,
                                    HealthEndpointAutoConfiguration.class))
                    .withPropertyValues(
                            "management.endpoint.health.probes.enabled=true",
                            // 이 컨텍스트는 실제 db/redis 컨트리뷰터 빈을 등록하지 않으므로(오직 group
                            // include 프로퍼티 바인딩만 검증), NoSuchHealthContributorException을
                            // 유발하는 기본 group membership 검증을 끈다.
                            "management.endpoint.health.validate-group-membership=false",
                            "management.endpoint.health.group.liveness.include=livenessState",
                            "management.endpoint.health.group.readiness.include=readinessState,db,redis");

    @Test
    @DisplayName("liveness 그룹은 livenessState만 포함하고 redis/db는 제외한다")
    void livenessGroup_excludesRedisAndDb() {
        contextRunner.run(
                context -> {
                    // Arrange
                    HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
                    HealthEndpointGroup liveness = groups.get("liveness");

                    // Act & Assert
                    assertThat(liveness).isNotNull();
                    assertThat(liveness.isMember("livenessState")).isTrue();
                    assertThat(liveness.isMember("redis")).isFalse();
                    assertThat(liveness.isMember("db")).isFalse();
                });
    }

    @Test
    @DisplayName("readiness 그룹은 readinessState/db/redis를 포함한다")
    void readinessGroup_includesReadinessStateDbAndRedis() {
        contextRunner.run(
                context -> {
                    // Arrange
                    HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
                    HealthEndpointGroup readiness = groups.get("readiness");

                    // Act & Assert
                    assertThat(readiness).isNotNull();
                    assertThat(readiness.isMember("readinessState")).isTrue();
                    assertThat(readiness.isMember("db")).isTrue();
                    assertThat(readiness.isMember("redis")).isTrue();
                });
    }
}
