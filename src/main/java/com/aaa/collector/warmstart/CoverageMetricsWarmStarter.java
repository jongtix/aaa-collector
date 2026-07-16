package com.aaa.collector.warmstart;

import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.CoverageRatioRepository;
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
 * 부팅 시 Redis에 영속된 커버리지 비율로 {@code aaa_collector_data_coverage_ratio} 게이지를 초기화한다
 * (SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-001).
 *
 * <p>커버리지는 일1회 크론이 계산해 다음 계산 전까지 유지하는 held 게이지다. 재시작하면 실제 값을 잃고 사전 등록된 0.0으로 리셋되어 vmalert가 "조건 해소"로
 * 오판하는 결함을 해소한다. {@link MarketIndicatorMetricsWarmStarter}를 미러한다.
 *
 * <p>대상 시리즈는 {@link WatermarkSeries#DAILY_OHLCV_KRX}·{@link WatermarkSeries#DAILY_OHLCV_US} 2종으로
 * 하드코딩한다 — {@code BackfillDensityMetrics.initGauges()}가 이미 이 2종을 하드코딩하는 것과 동일 수준의 정본이다. Redis 값이
 * 없거나 손상됐으면 게이지를 seed하지 않고 사전 등록된 0.0을 그대로 둔다(DB 프록시 폴백 없음). 시리즈별 조회 실패는 격리되어 나머지 시리즈의 seed를 막지
 * 않는다(REQ-WSR-024 철학 계승).
 */
// @MX:ANCHOR: [AUTO] 부팅 시 CoverageMetrics data_coverage_ratio gauge warm-start 진입점
// @MX:REASON: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-001 —
// Redis(CoverageRatioRepository) 단일 소스, 재시작 시 held 게이지 0.0 리셋 오탐 해소
// @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageMetricsWarmStarter implements ApplicationRunner {

    private static final List<WatermarkSeries> TARGET_SERIES =
            List.of(WatermarkSeries.DAILY_OHLCV_KRX, WatermarkSeries.DAILY_OHLCV_US);

    private final CoverageMetrics coverageMetrics;
    private final CoverageRatioRepository coverageRatioRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("CoverageMetrics warm-start 시작 (SPEC-COLLECTOR-WARMSTART-REDIS-004)");

        TARGET_SERIES.forEach(this::warm);

        log.info("CoverageMetrics warm-start 완료");
    }

    /**
     * Redis에 영속된 커버리지 비율로 게이지를 seed한다 (REQ-WSR4-001).
     *
     * <p>Redis 값이 없거나(손상 포함) 조회가 실패하면 게이지를 seed하지 않고 사전 등록된 0.0을 그대로 둔다.
     *
     * @param series 대상 시리즈
     */
    private void warm(WatermarkSeries series) {
        String label = series.seriesLabel();
        try {
            Optional<Double> ratio = coverageRatioRepository.find(label);
            if (ratio.isEmpty()) {
                log.debug("CoverageMetrics warm-start skip — series={} Redis 값 없음(0.0 유지)", label);
                return;
            }
            coverageMetrics.warmRatio(series, ratio.get());
            log.info(
                    "CoverageMetrics warm-start 완료(Redis) — series={}, ratio={}",
                    label,
                    ratio.get());
        } catch (DataAccessException e) {
            log.warn(
                    "CoverageMetrics warm-start Redis 조회 실패 — series={}, 무시하고 계속 진행. error={}",
                    label,
                    e.getMessage());
        }
    }
}
