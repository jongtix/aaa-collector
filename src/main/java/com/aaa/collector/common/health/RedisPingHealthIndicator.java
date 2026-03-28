package com.aaa.collector.common.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * PING 명령 기반 Redis 헬스 인디케이터.
 *
 * <p>Spring Boot 기본 {@code RedisHealthIndicator}는 {@code INFO} 명령을 사용하며, 이 명령은 Redis의
 * {@code @dangerous} 카테고리에 속한다. appuser ACL이 {@code -@dangerous}로 설정된 환경에서는 NOPERM 에러가 발생하므로, ACL
 * 통과가 보장되는 {@code @fast} 카테고리의 {@code PING}으로 대체한다.
 *
 * <p>{@link RedisHealthConfig}를 통해 {@code "redisHealthIndicator"}라는 Bean 이름으로 등록된다. 이 Bean 이름은
 * Spring Boot 자동 구성의 {@code @ConditionalOnMissingBean} 조건에 의해 기본 인디케이터 빈이 생성되지 않도록 한다. {@code
 * management.health.redis.enabled=false} 설정은 자동 구성의 {@code @ConditionalOnEnabledHealthIndicator}
 * 조건을 추가로 비활성화하여, 기본 인디케이터가 등록되지 않음을 이중으로 보장한다. 이 설정을 제거하면 기본 인디케이터가 부활하여 ACL NOPERM 에러가 재발할 수 있다.
 *
 * @see org.springframework.boot.actuate.data.redis.RedisHealthIndicator
 */
public class RedisPingHealthIndicator extends AbstractHealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public RedisPingHealthIndicator(RedisConnectionFactory connectionFactory) {
        super("Redis PING 헬스 체크 실패");
        this.connectionFactory = connectionFactory;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            builder.up().withDetail("ping", pong);
        }
    }
}
