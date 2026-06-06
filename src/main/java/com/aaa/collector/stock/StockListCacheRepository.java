package com.aaa.collector.stock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 관심 종목 목록을 Redis에 캐싱하는 레포지토리.
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>종목 목록: {@code cache:stock:list} — CachedStock JSON 배열, TTL 없음 (영구)
 * </ul>
 *
 * <p>캐시는 sync 성공 시에만 갱신되며 TTL이 없다. 직렬화/역직렬화 예외는 warn 로그만 남기고 전파하지 않는다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class StockListCacheRepository {

    static final String CACHE_KEY = "cache:stock:list";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 종목 목록을 JSON 직렬화하여 Redis에 저장한다.
     *
     * <p>TTL을 설정하지 않는다 — sync 성공 시에만 갱신하므로 영구 유지.
     *
     * <p>직렬화 예외 발생 시 warn 로그만 남기고 예외를 전파하지 않는다 (sync 계속 진행).
     *
     * @param stocks 저장할 종목 DTO 목록
     */
    // @MX:NOTE: [AUTO] TTL 미설정 의도: sync 성공 시에만 갱신
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 직렬화/Redis 예외 전부 포착해 sync 계속 진행
    public void save(List<CachedStock> stocks) {
        try {
            String json = objectMapper.writeValueAsString(stocks);
            redisTemplate.opsForValue().set(CACHE_KEY, json);
        } catch (Exception e) {
            log.warn("종목 목록 캐시 저장 실패 — 예외 무시하고 sync 계속 진행", e);
        }
    }

    /**
     * Redis에서 종목 목록을 조회한다.
     *
     * <p>캐시 미스(키 없음) 또는 역직렬화 예외 시 {@link Optional#empty()}를 반환한다.
     *
     * @return 캐시된 종목 목록. 캐시 미스 또는 역직렬화 예외 시 {@code Optional.empty()}
     */
    // @MX:NOTE: [AUTO] 하류 배치 수집(1-7) 소비 예정 — fan_in 3 이상 도달 시 ANCHOR으로 승격
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 역직렬화/Redis 예외 전부 포착해 캐시 미스로 처리
    public Optional<List<CachedStock>> findAll() {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_KEY);
            if (json == null) {
                return Optional.empty();
            }
            List<CachedStock> stocks = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(stocks);
        } catch (Exception e) {
            log.warn("종목 목록 캐시 조회 실패 — Optional.empty() 반환 (캐시 미스 처리)", e);
            return Optional.empty();
        }
    }

    /**
     * {@code cache:stock:list} 키를 삭제한다.
     *
     * <p>주로 테스트에서 캐시를 초기화할 때 사용한다.
     */
    public void delete() {
        redisTemplate.delete(CACHE_KEY);
    }
}
