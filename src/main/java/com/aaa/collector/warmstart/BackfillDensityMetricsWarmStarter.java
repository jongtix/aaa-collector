package com.aaa.collector.warmstart;

import com.aaa.collector.observability.BackfillDensityMetrics;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.WatermarkSeries;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 Redis에 영속된 값으로 밀도 게이지 A({@code aaa_collector_backfill_below_floor_stocks})·B({@code
 * aaa_collector_backfill_internal_gap_stocks})를 초기화한다 (SPEC-COLLECTOR-WARMSTART-REDIS-004
 * REQ-WSR4-002).
 *
 * <p>두 게이지는 일1회 크론이 계산해 다음 계산 전까지 유지하는 held 게이지다. 재시작하면 실제 값을 잃고 사전 등록된 0으로 리셋되어 vmalert가 "조건 해소"로
 * 오판하는 결함을 해소한다. {@link MarketIndicatorMetricsWarmStarter}를 미러한다.
 *
 * <p>게이지 2종(below-floor, internal-gap) × 시리즈 2종(KRX/US)을 모두 순회한다 — {@code
 * BackfillDensityMetrics.initGauges()}가 하드코딩하는 것과 동일 수준의 정본이다. Redis 값이 없거나 손상됐으면 게이지를 seed하지 않고 사전
 * 등록된 0을 그대로 둔다(DB 프록시 폴백 없음). 게이지×시리즈 조합별 조회 실패는 격리되어 한 조합의 실패가 다른 조합의 seed를 막지 않는다(REQ-WSR-024 철학
 * 계승).
 */
// @MX:ANCHOR: [AUTO] 부팅 시 BackfillDensityMetrics below_floor/internal_gap gauge warm-start 진입점
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-002 —
// Redis(BackfillDensityRepository) 단일 소스, 재시작 시 held 게이지 0 리셋 오탐 해소
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillDensityMetricsWarmStarter implements ApplicationRunner {

    private static final List<WatermarkSeries> TARGET_SERIES =
            List.of(WatermarkSeries.DAILY_OHLCV_KRX, WatermarkSeries.DAILY_OHLCV_US);

    private final BackfillDensityMetrics backfillDensityMetrics;
    private final BackfillDensityRepository backfillDensityRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("BackfillDensityMetrics warm-start 시작 (SPEC-COLLECTOR-WARMSTART-REDIS-004)");

        TARGET_SERIES.forEach(
                series -> {
                    warmBelowFloor(series);
                    warmInternalGap(series);
                });

        log.info("BackfillDensityMetrics warm-start 완료");
    }

    /**
     * Redis에 영속된 값으로 게이지 A(하한 미달)를 seed한다 (REQ-WSR4-002).
     *
     * <p>Redis 값이 없거나(손상 포함) 조회가 실패하면 게이지를 seed하지 않고 사전 등록된 0을 그대로 둔다.
     *
     * @param series 대상 시리즈
     */
    private void warmBelowFloor(WatermarkSeries series) {
        String label = series.seriesLabel();
        try {
            Optional<Long> count = backfillDensityRepository.findBelowFloor(label);
            if (count.isEmpty()) {
                log.debug(
                        "BackfillDensity below_floor warm-start skip — series={} Redis 값 없음(0 유지)",
                        label);
                return;
            }
            backfillDensityMetrics.warmBelowFloorCount(series, count.get());
            log.info(
                    "BackfillDensity below_floor warm-start 완료(Redis) — series={}, count={}",
                    label,
                    count.get());
        } catch (DataAccessException e) {
            log.warn(
                    "BackfillDensity below_floor warm-start Redis 조회 실패 — series={}, 무시하고 계속 진행."
                            + " error={}",
                    label,
                    e.getMessage());
        }
    }

    /**
     * Redis에 영속된 값으로 게이지 B(내부 구멍)를 seed한다 (REQ-WSR4-002).
     *
     * <p>Redis 값이 없거나(손상 포함) 조회가 실패하면 게이지를 seed하지 않고 사전 등록된 0을 그대로 둔다.
     *
     * @param series 대상 시리즈
     */
    private void warmInternalGap(WatermarkSeries series) {
        String label = series.seriesLabel();
        try {
            Optional<Long> count = backfillDensityRepository.findInternalGap(label);
            if (count.isEmpty()) {
                log.debug(
                        "BackfillDensity internal_gap warm-start skip — series={} Redis 값 없음(0 유지)",
                        label);
                return;
            }
            backfillDensityMetrics.warmInternalGapCount(series, count.get());
            log.info(
                    "BackfillDensity internal_gap warm-start 완료(Redis) — series={}, count={}",
                    label,
                    count.get());
        } catch (DataAccessException e) {
            log.warn(
                    "BackfillDensity internal_gap warm-start Redis 조회 실패 — series={}, 무시하고 계속 진행."
                            + " error={}",
                    label,
                    e.getMessage());
        }
    }
}
