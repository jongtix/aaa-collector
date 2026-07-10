package com.aaa.collector.common.health;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 헬스 인디케이터 설정 (aaa-infra#89).
 *
 * <p>{@link RedisConnectionFactory} 빈이 존재할 때만 {@link RedisPingHealthIndicator}를 등록한다.
 *
 * <p><b>{@code @AutoConfiguration(after = RedisAutoConfiguration.class)}를 사용하는 이유</b>: 일반 사용자
 * {@code @Configuration}은 auto-configuration보다 <em>먼저</em> 처리되므로, {@code RedisConnectionFactory}를
 * auto-configuration(RedisAutoConfiguration)이 만들기 전에 {@code @ConditionalOnBean}이 평가되어 항상 실패한다(실측
 * 확인: 일반 {@code @Configuration} + {@code @ConditionalOnBean}은 실 Redis 컨텍스트에서도 인디케이터 빈을 등록하지 못함 —
 * RedisHealthAutoConfigurationIntegrationTest가 RED로 재현). auto-configuration으로 선언하고 {@code
 * AutoConfiguration.imports}에 등록하면 (1) RedisAutoConfiguration 이후에 평가되어 {@code @ConditionalOnBean}이
 * 정상 동작하고, (2) {@code @SpringBootApplication}의 {@code AutoConfigurationExcludeFilter}가 컴포넌트 스캔에서 이
 * 클래스를 제외하므로 중복 등록도 발생하지 않는다.
 *
 * <p>smoke 프로파일처럼 RedisAutoConfiguration이 제외된 컨텍스트에서는 {@code RedisConnectionFactory}가 없으므로 이 인디케이터도
 * 등록되지 않는다.
 */
// @MX:NOTE: [AUTO] 일반 @Configuration+@ConditionalOnBean은 auto-configuration보다 먼저 평가되어 실 Redis
// 컨텍스트에서도 빈이 등록되지 않음(RedisHealthAutoConfigurationIntegrationTest가 RED로 확인) — @AutoConfiguration만
// 정상 등록. AutoConfiguration.imports 등록 필수.
@AutoConfiguration(after = RedisAutoConfiguration.class)
public class RedisHealthConfig {

    @Bean("redisHealthIndicator")
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisPingHealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return new RedisPingHealthIndicator(connectionFactory);
    }
}
