package com.aaa.collector.observability;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 데이터 커버리지 비율을 Redis에 영속화하는 레포지토리 (SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-001).
 *
 * <p>{@code aaa_collector_data_coverage_ratio{series}} 게이지(일1회 held 게이지)를 컨테이너 재시작 시 실제 최근 계산값으로
 * 복원하기 위한 seed 소스다. {@link CoverageMetrics#setRatio}가 계산값을 best-effort로 기록하고(write), {@code
 * CoverageMetricsWarmStarter}가 부팅 시 소비한다(read). {@link BatchLastLoadRepository}/{@link
 * com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository}를 미러한다(선행 REDIS-001/003과
 * 구분되는 후속 SPEC).
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>키: {@code observability:collector:data-coverage-ratio:{series}} — 커버리지 비율 문자열({@code
 *       Double.toString})
 *   <li>TTL 없음(영속) — held 게이지 값은 다음 일1회 계산까지 유효해야 한다
 * </ul>
 *
 * <p>write({@link #save})와 read({@link #find})는 동일한 {@link #key(String)} 파생 로직을 공유한다 — 키가 어긋나면 기록은
 * 되나 복원되지 않는 은묵 결함이 생기므로 단일 파생 소스로 캡슐화한다(REQ-WSR4-001).
 */
// @MX:ANCHOR: [AUTO] 데이터 커버리지 비율 Redis 영속화 단일 파생 소스 — setRatio(write)와
// CoverageMetricsWarmStarter(read)가 동일 키 파생을 공유한다.
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-001 — write/read 키 정합이 warm-start 정확도의 전제
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004
@Slf4j
@Repository
@RequiredArgsConstructor
public class CoverageRatioRepository {

    private static final String KEY_PREFIX = "observability:collector:data-coverage-ratio:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 시리즈 커버리지 비율을 TTL 없이(영속) 문자열로 저장한다 (REQ-WSR4-001).
     *
     * @param series 시리즈 라벨(예: {@code daily-ohlcv-krx})
     * @param ratio 커버리지 비율(게이지에 설정되는 값과 동일)
     */
    public void save(String series, double ratio) {
        redisTemplate.opsForValue().set(key(series), Double.toString(ratio));
    }

    /**
     * 시리즈 커버리지 비율을 조회한다 (REQ-WSR4-001 read-side).
     *
     * <p>저장값이 손상되면 예외를 전파하지 않고 {@code Optional.empty()}로 흡수한다 — 비숫자라 파싱에 실패하는 경우({@link
     * NumberFormatException}). 손상값은 "미가용"의 한 형태이므로 소비자({@code CoverageMetricsWarmStarter})가 사전 등록된
     * 0.0을 그대로 두게 해야 한다(DB 프록시 폴백 없음).
     *
     * @param series 시리즈 라벨
     * @return 저장된 커버리지 비율. 키가 없거나 값이 손상됐으면(비숫자) {@code Optional.empty()}
     */
    public Optional<Double> find(String series) {
        String raw = redisTemplate.opsForValue().get(key(series));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            log.warn(
                    "CoverageRatio 손상값 무시 — series={}, raw={} (seed 생략). error={}",
                    series,
                    raw,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private String key(String series) {
        return KEY_PREFIX + series;
    }
}
