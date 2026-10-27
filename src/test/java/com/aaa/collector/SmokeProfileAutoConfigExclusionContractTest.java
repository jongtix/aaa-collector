package com.aaa.collector;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;

/**
 * {@code smoke}·{@code db-integration} 프로파일 자동구성 제외 계약 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-012,
 * AC-BOOT4-012 (b)).
 *
 * <p>Spring Boot는 {@code spring.autoconfigure.exclude}에 적힌 클래스가 클래스패스에 없으면 오류 없이 무시한다. Boot 메이저
 * 전환으로 자동구성 클래스의 패키지·이름이 바뀌면 제외만 조용히 사라지므로, ① 제외 항목 각각이 클래스패스에 있는 등록된 자동구성 후보이고 ② smoke 컨텍스트에 제외
 * 대상이 만드는 빈이 없음을 단언한다. {@code db-integration}은 컨테이너가 필요한 프로파일이라 ①만 YAML 바인딩으로 판정한다.
 */
@SpringBootTest
@ActiveProfiles({"test", "smoke"})
@DisplayName("테스트 프로파일 자동구성 제외 계약 — 제외 항목이 실제 자동구성을 가리킨다")
class SmokeProfileAutoConfigExclusionContractTest extends SmokeMockitoBase {

    /** smoke 프로파일 제외 항목 수 기준선(DataSource·HibernateJpa·Flyway·Redis·RedisRepositories). */
    private static final int SMOKE_EXCLUSION_COUNT = 5;

    /** db-integration 프로파일 제외 항목 수 기준선(Redis·RedisRepositories). */
    private static final int DB_INTEGRATION_EXCLUSION_COUNT = 2;

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    @Autowired private ApplicationContext context;

    @Autowired private Environment environment;

    private static void assertRegisteredAutoConfigurations(
            List<String> exclusions, int expectedCount) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Set<String> candidates = new HashSet<>();
        ImportCandidates.load(AutoConfiguration.class, classLoader).forEach(candidates::add);

        assertThat(exclusions).hasSize(expectedCount);
        assertThat(exclusions).allMatch(name -> ClassUtils.isPresent(name, classLoader));
        assertThat(candidates).containsAll(exclusions);
    }

    @Nested
    @DisplayName("smoke 프로파일")
    class Smoke {

        @Test
        @DisplayName("제외 항목이 모두 클래스패스에 있는 등록된 자동구성 후보다")
        void exclusionsAreRegisteredAutoConfigurations() {
            List<String> exclusions =
                    Binder.get(environment)
                            .bind(EXCLUDE_KEY, Bindable.listOf(String.class))
                            .orElse(List.of());

            assertRegisteredAutoConfigurations(exclusions, SMOKE_EXCLUSION_COUNT);
        }

        @Test
        @DisplayName(
                "smoke 컨텍스트에 DataSource·EntityManagerFactory·Flyway·RedisConnectionFactory 빈이 없다")
        void excludedBeansAreAbsent() {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
            assertThat(context.getBeanNamesForType(RedisConnectionFactory.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("db-integration 프로파일")
    class DbIntegration {

        @Test
        @DisplayName("application-db-integration.yml 제외 항목이 모두 등록된 자동구성 후보다")
        void exclusionsAreRegisteredAutoConfigurations() throws IOException {
            // Arrange
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader()
                            .load(
                                    "db-integration",
                                    new ClassPathResource("application-db-integration.yml"));

            // Act
            List<String> exclusions =
                    new Binder(ConfigurationPropertySources.from(sources))
                            .bind(EXCLUDE_KEY, Bindable.listOf(String.class))
                            .orElse(List.of());

            // Assert
            assertRegisteredAutoConfigurations(exclusions, DB_INTEGRATION_EXCLUSION_COUNT);
        }
    }
}
