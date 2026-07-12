package com.aaa.collector.observability;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 배치별 마지막 성공 완료 시각을 Redis에 영속화하는 레포지토리 (SPEC-COLLECTOR-WARMSTART-REDIS-001).
 *
 * <p>{@code aaa_collector_batch_last_load_seconds{batch}} 게이지("배치 마지막 완료 시각")를 컨테이너 재시작 시 DB 프록시
 * 근사가 아니라 실제 마지막 성공 시각으로 복원하기 위한 seed 소스다. {@link BatchMetrics#recordCompletion}이 완료 stamp 시각을
 * best-effort로 기록하고(write), {@code BatchMetricsWarmStarter}가 부팅 시 우선 소비한다(read).
 *
 * <p>Redis Key 설계:
 *
 * <ul>
 *   <li>키: {@code observability:collector:last-load:{batch}} — UTC epoch 초 문자열
 *   <li>TTL 없음(영속) — "마지막 성공 시각"은 다음 성공까지 유효해야 하므로({@code SafeModeRepository}의 backoff 키와 동일 정책)
 * </ul>
 *
 * <p>write({@link #save})와 read({@link #find})는 동일한 {@link #key(String)} 파생 로직을 공유한다 — 키가 어긋나면 기록은
 * 되나 복원되지 않는 은묵 결함이 생기므로 단일 파생 소스로 캡슐화한다(REQ-WSR-008).
 */
// @MX:ANCHOR: [AUTO] 배치 last-load 성공 시각 Redis 영속화 단일 파생 소스 — recordCompletion(write)과
// BatchMetricsWarmStarter(read)가 동일 키 파생을 공유한다.
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-001 REQ-WSR-008 — write/read 키 정합이 warm-start 정확도의 전제
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-001
@Slf4j
@Repository
@RequiredArgsConstructor
public class BatchLastLoadRepository {

    private static final String KEY_PREFIX = "observability:collector:last-load:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 배치 라벨의 마지막 성공 epoch를 TTL 없이(영속) UTC epoch 초 문자열로 저장한다 (REQ-WSR-002).
     *
     * @param batch 배치 라벨(예: {@code corp-code})
     * @param epochSeconds 마지막 성공 시각의 UTC epoch 초(게이지에 stamp되는 값과 동일)
     */
    public void save(String batch, long epochSeconds) {
        redisTemplate.opsForValue().set(key(batch), Long.toString(epochSeconds));
    }

    /**
     * 배치 라벨의 마지막 성공 시각을 조회한다 (REQ-WSR-005 read-side).
     *
     * <p>저장된 UTC epoch 초 문자열을 {@link Instant}로 환원해 반환한다 — 소비자({@code BatchMetricsWarmStarter})가 게이지
     * seed에 그대로 쓰는 형태다. epoch↔Instant 변환을 이 단일 파생 소스에 캡슐화한다.
     *
     * <p>저장값이 손상되어(비숫자) 파싱에 실패하면 {@link NumberFormatException}을 전파하지 않고 {@code Optional.empty()}로
     * 흡수한다 — 손상값은 "미가용"의 한 형태이므로 소비자({@code BatchMetricsWarmStarter})가 DB 프록시로 폴백하게 해야 부팅이 크래시하지
     * 않는다(REQ-WSR-006, 비회귀).
     *
     * @param batch 배치 라벨(예: {@code corp-code})
     * @return 저장된 마지막 성공 시각. 키가 없거나 값이 손상됐으면 {@code Optional.empty()}
     */
    public Optional<Instant> find(String batch) {
        String raw = redisTemplate.opsForValue().get(key(batch));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(raw)));
        } catch (NumberFormatException e) {
            log.warn(
                    "BatchLastLoad 손상값 무시 — batch={}, raw={} (프록시 폴백). error={}",
                    batch,
                    raw,
                    e.getMessage());
            return Optional.empty();
        }
    }

    private String key(String batch) {
        return KEY_PREFIX + batch;
    }
}
