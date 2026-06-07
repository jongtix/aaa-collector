package com.aaa.collector.stock.grade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 종목 등급을 Redis에 캐싱하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>등급: {@code cache:grade:{symbol}} — JSON 문자열, TTL 없음 (영구)
 * </ul>
 *
 * <p>캐시는 등급 분류 성공 시에만 갱신되며 TTL이 없다. 직렬화/Redis 예외는 warn 로그만 남기고 전파하지 않는다 (non-fatal, REQ-009/010).
 *
 * <p>JSON 형식: {@code {"grade":"A","gradedAt":"2026-06-07T09:00:00+09:00"}}
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class GradeCacheRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 종목 등급을 JSON 직렬화하여 Redis에 저장한다.
     *
     * <p>TTL을 설정하지 않는다 — 등급 분류 성공 시에만 갱신하므로 영구 유지.
     *
     * <p>예외 발생 시 warn 로그만 남기고 예외를 전파하지 않는다 (등급 분류 계속 진행).
     *
     * @param symbol 종목코드
     * @param grade 등급
     * @param gradedAt 등급 산정 시각 (KST)
     */
    // @MX:NOTE: [AUTO] TTL 미설정 의도: 등급 분류 성공 시에만 갱신, SPEC-CACHE-001 준수
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 직렬화/Redis 예외 전부 포착해 등급 분류 계속 진행
    public void save(String symbol, Grade grade, ZonedDateTime gradedAt) {
        try {
            String json =
                    objectMapper.writeValueAsString(
                            Map.of("grade", grade.name(), "gradedAt", gradedAt));
            redisTemplate.opsForValue().set("cache:grade:" + symbol, json);
        } catch (JsonProcessingException e) {
            log.warn("등급 캐시 JSON 직렬화 실패 — symbol={}, grade={}", symbol, grade, e);
        } catch (Exception e) {
            log.warn("등급 캐시 저장 실패 (Redis 오류) — symbol={}, grade={}", symbol, grade, e);
        }
    }
}
