package com.aaa.collector.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 설정.
 *
 * <p>JPA AutoConfiguration을 exclude하는 테스트 프로파일(예: smoke)에서는 {@code
 * spring.jpa.auditing.enabled=false}를 설정해야 한다. 미설정 시 {@code AuditingEntityListener} 빈 미존재로 컨텍스트 로드가
 * 실패한다.
 */
@Configuration
@EnableJpaAuditing
@ConditionalOnProperty(name = "spring.jpa.auditing.enabled", matchIfMissing = true)
public class JpaConfig {}
