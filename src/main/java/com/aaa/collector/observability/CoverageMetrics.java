package com.aaa.collector.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 데이터 커버리지 게이지 계측 (SPEC-OBSV-WATERMARK-001 REQ-WM-010/011).
 *
 * <p>{@value #COVERAGE_NAME}{@code {series}} — §3 사전에서 커버리지=ACTIVE로 표시된 시리즈(daily-ohlcv-krx/us)에 한해
 * 일1회 (deadline 직후) 계산된 커버리지 비율을 노출한다. 계측 자체는 임의 {@link WatermarkSeries}를 받되, 실제로 값이 설정되는 시리즈는
 * {@link CoverageRefresher}가 결정한다 — 사전 외 시리즈에도 게이지 등록이 가능하나 §3에서 커버리지 대상은 2종으로 한정된다(CR-02, 미검증 밀도
 * 시리즈 커버리지 룰 생성 금지).
 */
// @MX:NOTE: [AUTO] 데이터 커버리지 게이지 갱신 진입점 — CoverageRefresher 일1회 계산에서 호출
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-010/011
@Component
@RequiredArgsConstructor
public class CoverageMetrics {

    static final String COVERAGE_NAME = "aaa_collector_data_coverage_ratio";

    private final MeterRegistry registry;

    private final Map<WatermarkSeries, DoubleAdder> holders = new ConcurrentHashMap<>();

    /**
     * 시리즈 커버리지 비율을 설정한다(0.0~1.0, 절대값 — forward-only 제약 없음).
     *
     * @param series 대상 시리즈(daily-ohlcv-krx/us)
     * @param ratio 커버리지 비율(분자/분모, 분모 0이면 호출자가 0.0 전달)
     */
    public void setRatio(WatermarkSeries series, double ratio) {
        DoubleAdder holder = holder(series);
        holder.reset();
        holder.add(ratio);
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
