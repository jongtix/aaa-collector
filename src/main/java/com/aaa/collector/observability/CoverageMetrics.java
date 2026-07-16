package com.aaa.collector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 데이터 커버리지 게이지 계측 (SPEC-OBSV-WATERMARK-001 REQ-WM-010/011).
 *
 * <p>{@value #COVERAGE_NAME}{@code {series}} — §3 사전에서 커버리지=ACTIVE로 표시된 시리즈(daily-ohlcv-krx/us)에 한해
 * 일1회 (deadline 직후) 계산된 커버리지 비율을 노출한다. 계측 자체는 임의 {@link WatermarkSeries}를 받되, 실제로 값이 설정되는 시리즈는
 * {@link CoverageRefresher}가 결정한다 — 사전 외 시리즈에도 게이지 등록이 가능하나 §3에서 커버리지 대상은 2종으로 한정된다(CR-02, 미검증 밀도
 * 시리즈 커버리지 룰 생성 금지).
 *
 * <p>일1회 held 게이지라 재시작 시 실제 값을 잃고 스크랩 부재/0으로 리셋되면 vmalert가 조건 해소로 오판한다. 이를 막기 위해 (1) 부팅 직후 두
 * 시장(KRX/US) 라벨을 0.0으로 pre-register하고({@link #initGauges}, {@link BackfillDensityMetrics}와 동일 목적),
 * (2) {@link #setRatio} 시 계산값을 Redis에 best-effort로 영속화하며({@link CoverageRatioRepository}), (3) 부팅 시
 * {@code CoverageMetricsWarmStarter}가 {@link #warmRatio}로 실제 값을 복원한다
 * (SPEC-COLLECTOR-WARMSTART-REDIS-004).
 */
// @MX:NOTE: [AUTO] 데이터 커버리지 게이지 갱신 진입점 — CoverageRefresher 일1회 계산에서 호출, Redis write-through
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-010/011, SPEC-COLLECTOR-WARMSTART-REDIS-004
// REQ-WSR4-001
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageMetrics {

    static final String COVERAGE_NAME = "aaa_collector_data_coverage_ratio";

    private final MeterRegistry registry;
    private final CoverageRatioRepository coverageRatioRepository;

    private final Map<WatermarkSeries, DoubleAdder> holders = new ConcurrentHashMap<>();

    /**
     * 부팅 직후 KRX/US 두 시장 라벨을 0.0으로 pre-register한다 (REQ-WSR4-001, 스크랩 공백 방지).
     *
     * <p>{@link BackfillDensityMetrics#initGauges}와 동일한 이유(REQ-156 미러) — 웜스타트가 있어도 Redis에 값이 아직 없는
     * 최초 배포 등에서는 스크랩 공백이 남으므로, 사전 등록으로 근본 대칭성을 맞춘다.
     */
    @PostConstruct
    void initGauges() {
        holder(WatermarkSeries.DAILY_OHLCV_KRX);
        holder(WatermarkSeries.DAILY_OHLCV_US);
    }

    /**
     * 시리즈 커버리지 비율을 설정한다(0.0~1.0, 절대값 — forward-only 제약 없음).
     *
     * <p>게이지 설정과 함께 계산값을 Redis에 best-effort로 영속화한다 — 재시작 시 {@code CoverageMetricsWarmStarter}가 이 값을
     * 복원한다(REQ-WSR4-001).
     *
     * @param series 대상 시리즈(daily-ohlcv-krx/us)
     * @param ratio 커버리지 비율(분자/분모, 분모 0이면 호출자가 0.0 전달)
     */
    public void setRatio(WatermarkSeries series, double ratio) {
        DoubleAdder holder = holder(series);
        holder.reset();
        holder.add(ratio);
        persistRatio(series, ratio);
    }

    /**
     * 부팅 시 Redis에 영속된 커버리지 비율로 게이지를 초기화한다 (REQ-WSR4-001, {@code
     * MarketIndicatorMetrics#warmLastSuccess} 미러).
     *
     * <p>{@link #setRatio}와 달리 Redis에 다시 쓰지 않는다(웜스타터 전용) — 복원 값을 그대로 재기록하는 낭비를 피한다.
     *
     * @param series 대상 시리즈
     * @param ratio Redis에서 조회한 커버리지 비율
     */
    // @MX:NOTE: [AUTO] setRatio(write)의 read-side 미러 — CoverageMetricsWarmStarter가 부팅 시 호출
    // @MX:SPEC: SPEC-COLLECTOR-WARMSTART-REDIS-004 REQ-WSR4-001
    public void warmRatio(WatermarkSeries series, double ratio) {
        DoubleAdder holder = holder(series);
        holder.reset();
        holder.add(ratio);
    }

    /**
     * 커버리지 비율을 Redis에 best-effort로 기록한다 (REQ-WSR4-001).
     *
     * <p>기록 실패는 게이지 기록·계산 흐름을 중단시키지 않는다 — {@link DataAccessException}을 warn 로깅 후 흡수하고 계속 진행한다
     * ({@code MarketIndicatorMetrics#persistLastSuccess} 격리 철학 계승).
     */
    private void persistRatio(WatermarkSeries series, double ratio) {
        try {
            coverageRatioRepository.save(series.seriesLabel(), ratio);
        } catch (DataAccessException e) {
            log.warn(
                    "[coverage-metrics] 커버리지 비율 Redis 기록 실패 — series={}, 무시하고 계속 진행. error={}",
                    series.seriesLabel(),
                    e.getMessage());
        }
    }

    private DoubleAdder holder(WatermarkSeries series) {
        return holders.computeIfAbsent(
                series,
                s -> {
                    DoubleAdder adder = new DoubleAdder();
                    registry.gauge(
                            COVERAGE_NAME,
                            Tags.of("series", s.seriesLabel()),
                            adder,
                            DoubleAdder::doubleValue);
                    return adder;
                });
    }
}
