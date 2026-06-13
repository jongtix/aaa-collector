package com.aaa.collector.common.startup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 권한 self-check 빈 구성.
 *
 * <p>{@link JdbcTemplate}이 존재하는 경우(실제 DataSource 환경)에만 {@link InformationSchemaGrantLoader}와 {@link
 * DbGrantCheckRunner}를 빈으로 등록한다. DataSource가 없는 smoke 테스트 환경에서는 이 빈들이 생성되지 않는다.
 */
@Configuration
public class DbGrantCheckConfig {

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public InformationSchemaGrantLoader informationSchemaGrantLoader(JdbcTemplate jdbcTemplate) {
        return new InformationSchemaGrantLoader(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(DbGrantLoader.class)
    public DbGrantCheckRunner dbGrantCheckRunner(DbGrantLoader loader, DbGrantVerifier verifier) {
        return new DbGrantCheckRunner(loader, verifier);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    public DbGrantVerifier dbGrantVerifier() {
        return new DbGrantVerifier();
    }
}
