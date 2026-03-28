package com.aaa.collector.common.health;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 헬스 인디케이터 설정 클래스.
 *
 * <p>{@link RedisConnectionFactory} 빈이 존재할 때만 {@link RedisPingHealthIndicator}를 등록한다.
 */
@Configuration
public class RedisHealthConfig {

    @Bean("redisHealthIndicator")
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisPingHealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
        return new RedisPingHealthIndicator(connectionFactory);
    }
}
