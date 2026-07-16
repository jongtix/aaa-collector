package com.aaa.collector.observability;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 과거 데이터 밀도 게이지(하한 미달·내부 구멍) 종목 수를 Redis에 영속화하는 레포지토리 (SPEC-COLLECTOR-WARMSTART-REDIS-004
 * REQ-WSR4-002).
 *
 * <p>{@code aaa_collector_backfill_below_floor_stocks{series,market}}·{@code
 * aaa_collector_backfill_internal_gap_stocks{series,market}} 두 held 게이지를 컨테이너 재시작 시 실제 최근 계산값으로
 * 복원하기 위한 seed 소스다. {@link BackfillDensityMetrics}가 두 게이지를 한 클래스에서 소유하는 것과 대칭으로, 이 레포지토리도 두 키를 한
 * 클래스에서 담당한다. {@link BackfillDensityMetrics#setBelowFloorCount}/{@link
 * BackfillDensityMetrics#setInternalGapCount}가 계산값을 best-effort로 기록하고(write), {@code
 * BackfillDensityMetricsWarmStarter}가 부팅 시 소비한다(read).
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>키: {@code observability:collector:backfill-below-floor:{series}} / {@code
 *       observability:collector:backfill-internal-gap:{series}} — 종목 수 문자열
 *   <li>TTL 없음(영속) — held 게이지 값은 다음 일1회 계산까지 유효해야 한다
 * </ul>
 *
 * <p>게이지별 write/read는 동일한 키 파생 로직({@link #key(String, String)})을 공유한다 — 키가 어긋나면 기록은 되나 복원되지 않는 은묵
 * 결함이 생기므로 단일 파생 소스로 캡슐화한다(REQ-WSR4-002).
 */
// @MX:ANCHOR: [AUTO] 밀도 게이지 A(하한 미달)·B(내부 구멍) Redis 영속화 단일 파생 소스 — set*(write)와
// BackfillDensityMetricsWarmStarter(read)가 동일 키 파생을 공유한다.
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-002 — write/read 키 정합이 warm-start 정확도의 전제
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004
@Slf4j
@Repository
@RequiredArgsConstructor
public class BackfillDensityRepository {

    private static final String BELOW_FLOOR_PREFIX =
            "observability:collector:backfill-below-floor:";
    private static final String INTERNAL_GAP_PREFIX =
            "observability:collector:backfill-internal-gap:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 게이지 A(하한 미달 종목 수)를 TTL 없이(영속) 저장한다 (REQ-WSR4-002).
     *
     * @param series 시리즈 라벨(예: {@code daily-ohlcv-krx})
     * @param count 하한 미달 종목 수
     */
    public void saveBelowFloor(String series, long count) {
        redisTemplate.opsForValue().set(key(BELOW_FLOOR_PREFIX, series), Long.toString(count));
    }

    /**
     * 게이지 A(하한 미달 종목 수)를 조회한다 (REQ-WSR4-002 read-side).
     *
     * @param series 시리즈 라벨
     * @return 저장된 하한 미달 종목 수. 키가 없거나 값이 손상됐으면(비숫자) {@code Optional.empty()}
     */
    public Optional<Long> findBelowFloor(String series) {
        return find(BELOW_FLOOR_PREFIX, series);
    }

    /**
     * 게이지 B(내부 구멍 보유 종목 수)를 TTL 없이(영속) 저장한다 (REQ-WSR4-002).
     *
     * @param series 시리즈 라벨(예: {@code daily-ohlcv-krx})
     * @param count 내부 구멍 보유 종목 수
     */
    public void saveInternalGap(String series, long count) {
        redisTemplate.opsForValue().set(key(INTERNAL_GAP_PREFIX, series), Long.toString(count));
    }

    /**
     * 게이지 B(내부 구멍 보유 종목 수)를 조회한다 (REQ-WSR4-002 read-side).
     *
     * @param series 시리즈 라벨
     * @return 저장된 내부 구멍 보유 종목 수. 키가 없거나 값이 손상됐으면(비숫자) {@code Optional.empty()}
     */
    public Optional<Long> findInternalGap(String series) {
        return find(INTERNAL_GAP_PREFIX, series);
    }

    /**
     * 접두어별 종목 수를 조회한다.
     *
     * <p>저장값이 손상되면 예외를 전파하지 않고 {@code Optional.empty()}로 흡수한다 — 비숫자라 파싱에 실패하는 경우({@link
     * NumberFormatException}). 손상값은 "미가용"의 한 형태이므로 소비자({@code BackfillDensityMetricsWarmStarter})가
     * 사전 등록된 0을 그대로 두게 해야 한다(DB 프록시 폴백 없음).
     */
    private Optional<Long> find(String prefix, String series) {
        String raw = redisTemplate.opsForValue().get(key(prefix, series));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            log.warn(
                    "BackfillDensity 손상값 무시 — key={}, raw={} (seed 생략). error={}",
                    key(prefix, series),
                    raw,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private String key(String prefix, String series) {
        return prefix + series;
    }
}
