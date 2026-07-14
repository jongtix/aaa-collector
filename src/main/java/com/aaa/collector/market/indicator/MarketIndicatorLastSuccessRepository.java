package com.aaa.collector.market.indicator;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 시장지표 지표×소스별 마지막 성공 시각을 Redis에 영속화하는 레포지토리 (SPEC-COLLECTOR-WARMSTART-REDIS-003).
 *
 * <p>{@code aaa_collector_market_indicator_source_last_success_seconds{indicator, source}} 게이지를
 * 컨테이너 재시작 시 실제 마지막 성공 시각으로 복원하기 위한 seed 소스다. {@link MarketIndicatorMetrics#recordSuccess}가 성공 시각을
 * best-effort로 기록하고(write), {@code MarketIndicatorMetricsWarmStarter}가 부팅 시 소비한다(read). {@link
 * com.aaa.collector.observability.BatchLastLoadRepository}를 미러한다(REQ-WSR-017).
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>키: {@code observability:collector:market-indicator-last-success:{indicator}:{source}} — UTC
 *       epoch 초 문자열
 *   <li>TTL 없음(영속) — "마지막 성공 시각"은 다음 성공까지 유효해야 한다
 * </ul>
 *
 * <p>write({@link #save})와 read({@link #find})는 동일한 {@link #key(String, String)} 파생 로직을 공유한다 — 키가
 * 어긋나면 기록은 되나 복원되지 않는 은묵 결함이 생기므로 단일 파생 소스로 캡슐화한다(REQ-WSR-017).
 */
// @MX:ANCHOR: [AUTO] 시장지표 소스 성공 시각 Redis 영속화 단일 파생 소스 — recordSuccess(write)와
// MarketIndicatorMetricsWarmStarter(read)가 동일 키 파생을 공유한다.
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-003 REQ-WSR-017 — write/read 키 정합이 warm-start 정확도의 전제
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-003
@Slf4j
@Repository
@RequiredArgsConstructor
public class MarketIndicatorLastSuccessRepository {

    private static final String KEY_PREFIX =
            "observability:collector:market-indicator-last-success:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 지표×소스의 마지막 성공 epoch를 TTL 없이(영속) UTC epoch 초 문자열로 저장한다 (REQ-WSR-017).
     *
     * @param indicator 지표 식별자(예: {@code VIX})
     * @param source 성공한 소스 이름(예: {@code CBOE})
     * @param epochSeconds 마지막 성공 시각의 UTC epoch 초(게이지에 stamp되는 값과 동일)
     */
    public void save(String indicator, String source, long epochSeconds) {
        redisTemplate.opsForValue().set(key(indicator, source), Long.toString(epochSeconds));
    }

    /**
     * 지표×소스의 마지막 성공 시각을 조회한다 (REQ-WSR-021 read-side).
     *
     * <p>저장된 UTC epoch 초 문자열을 {@link Instant}로 환원해 반환한다 — 소비자({@code
     * MarketIndicatorMetricsWarmStarter})가 게이지 seed에 그대로 쓰는 형태다.
     *
     * <p>저장값이 손상되면 예외를 전파하지 않고 {@code Optional.empty()}로 흡수한다 — (1) 비숫자라 파싱에 실패하는 경우 ({@link
     * NumberFormatException}), (2) 파싱은 되나 {@link Instant}의 유효 epoch-second 범위를 벗어나는 경우 ({@link
     * DateTimeException}) 둘 다 해당한다. 손상값은 "미가용"의 한 형태이므로 소비자가 사전 등록된 0.0을 그대로 두게 해야 한다(REQ-WSR-020,
     * REQ-WSR-022 — DB 프록시 폴백 없음).
     *
     * @param indicator 지표 식별자
     * @param source 소스 이름
     * @return 저장된 마지막 성공 시각. 키가 없거나 값이 손상됐으면(비숫자 또는 범위 밖) {@code Optional.empty()}
     */
    public Optional<Instant> find(String indicator, String source) {
        String raw = redisTemplate.opsForValue().get(key(indicator, source));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(raw)));
        } catch (NumberFormatException | DateTimeException e) {
            log.warn(
                    "MarketIndicatorLastSuccess 손상값 무시 — indicator={}, source={}, raw={} (seed 생략)."
                            + " error={}",
                    indicator,
                    source,
                    raw,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private String key(String indicator, String source) {
        return KEY_PREFIX + indicator + ":" + source;
    }
}
