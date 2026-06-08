package com.aaa.collector.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 종목 등급 캐시 리포지토리.
 *
 * <p>Redis Key: {@code cache:grade:{symbol}}
 *
 * <p>TTL 없음. 등급 변경 시에만 갱신. Redis 저장 실패는 warn 로그만 남기고 예외 비전파 (REQ-ETFCACHE-002).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GradeCacheRepository {

    static final String KEY_PREFIX = "cache:grade:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Stores the grade for a symbol in Redis.
     *
     * <p>Failure is non-fatal: logs a warning and returns without propagating the exception
     * (REQ-ETFCACHE-002). DB transaction is unaffected.
     *
     * @param symbol stock ticker symbol
     * @param grade grade string (A/B/C/F)
     */
    @SuppressWarnings(
            "PMD.AvoidCatchingGenericException") // Redis failure must not fail DB transaction
    public void set(String symbol, String grade) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + symbol, grade);
        } catch (Exception e) {
            log.warn(
                    "Grade cache update failed for symbol={} — continuing without cache update",
                    symbol,
                    e);
        }
    }

    /**
     * Returns the cached grade for a symbol, or null if absent.
     *
     * <p>Failure is non-fatal.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public String get(String symbol) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + symbol);
        } catch (Exception e) {
            log.warn("Grade cache read failed for symbol={}", symbol, e);
            return null;
        }
    }
}
