package com.aaa.collector.common.safemode;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 안전 모드 Bean 설정.
 *
 * <p>토큰 컨텍스트와 WebSocket 컨텍스트에서 각각 독립적인 네임스페이스를 사용하는 {@link SafeModeManager} Bean을 등록한다.
 */
@Configuration
public class SafeModeConfig {

    /** KIS 토큰 안전 모드 관리자. Redis 키: {@code safe_mode:collector:token:{alias}} */
    @Bean
    public SafeModeManager tokenSafeModeManager(StringRedisTemplate redisTemplate) {
        return new SafeModeManager(
                new SafeModeRepository(redisTemplate, "safe_mode:collector:token:"));
    }

    /** KIS WebSocket 안전 모드 관리자. Redis 키: {@code safe_mode:collector:ws:{alias}} */
    @Bean
    public SafeModeManager webSocketSafeModeManager(StringRedisTemplate redisTemplate) {
        return new SafeModeManager(
                new SafeModeRepository(redisTemplate, "safe_mode:collector:ws:"));
    }
}
