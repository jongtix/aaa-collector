package com.aaa.collector.common.startup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 권한 self-check 빈 구성.
 *
 * <p>{@code collector.db-grant-check.enabled} 프로퍼티(기본값 {@code true} — 미설정 시에도 활성)로 {@link
 * InformationSchemaGrantLoader}, {@link DbGrantVerifier}, {@link DbGrantCheckRunner} 세 빈의 등록 여부를
 * 결정한다.
 *
 * <p><b>{@code @ConditionalOnBean(JdbcTemplate.class)}를 쓰지 않는 이유(aaa-infra#120)</b>: 이 클래스는
 * {@code @Configuration}(auto-configuration이 아닌 사용자 구성 클래스)이다. Spring Boot는 사용자
 * {@code @Configuration}의 빈 등록 조건을 {@code JdbcTemplateAutoConfiguration}이 {@link JdbcTemplate} 빈을
 * 등록하기 **이전**에 평가한다 — 사용자 구성 클래스가 자동 구성보다 먼저 처리되는 것은 Spring Boot의 일반 컨텍스트 초기화 순서다. 따라서
 * {@code @ConditionalOnBean(JdbcTemplate.class)}는 실제 DataSource가 존재하는 프로덕션 환경에서도 항상 미충족으로 평가되어 세 빈이
 * 조용히(오류 로그 없이) 등록되지 않는다 — "{@code @ConditionalOnBean}은 auto-configuration 클래스에서만 신뢰할 수 있다"는 잘 알려진
 * Spring Boot 함정이다. 이 결함으로 인해 fail-fast DB 권한 self-check(ADR-026 결정 4, SPEC-INFRA-DBGRANT-001)가 배포
 * 후 90일간 단 한 번도 실행되지 않았다(실측: 프로덕션 로그에 {@link DbGrantCheckRunner} 출력 0건).
 *
 * <p>{@code @ConditionalOnProperty}는 컨텍스트 초기화 순서와 무관하게 {@code Environment}만으로 즉시 평가되므로 이 함정에서 자유롭다.
 * smoke 테스트 환경(실제 DataSource 없음)에서는 {@code collector.db-grant-check.enabled=false}를 명시 설정해
 * 비활성화한다({@code application-smoke.yml} 참고) — 그렇지 않으면 {@link JdbcTemplate} 빈이 없는 컨텍스트에서 빈 생성 자체가
 * 실패한다.
 *
 * @see <a href="https://github.com/jongtix/aaa-infra/issues/120">aaa-infra#120</a>
 */
@Configuration
@ConditionalOnProperty(
        name = "collector.db-grant-check.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DbGrantCheckConfig {

    @Bean
    public InformationSchemaGrantLoader informationSchemaGrantLoader(JdbcTemplate jdbcTemplate) {
        return new InformationSchemaGrantLoader(jdbcTemplate);
    }

    @Bean
    public DbGrantCheckRunner dbGrantCheckRunner(DbGrantLoader loader, DbGrantVerifier verifier) {
        return new DbGrantCheckRunner(loader, verifier);
    }

    @Bean
    public DbGrantVerifier dbGrantVerifier() {
        return new DbGrantVerifier();
    }
}
