package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CoverageMetrics — 데이터 커버리지 게이지 (REQ-WM-010/011)")
class CoverageMetricsTest {

    @Test
    @DisplayName("setRatio 호출 시 series 라벨로 게이지가 등록되고 값이 설정된다")
    void setRatio_registersGaugeWithSeriesTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoverageMetrics metrics = new CoverageMetrics(registry);

        metrics.setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.955);

        Gauge gauge =
                registry.get(CoverageMetrics.COVERAGE_NAME)
                        .tags("series", "daily-ohlcv-krx")
                        .gauge();
        assertThat(gauge.value()).isEqualTo(0.955);
    }

    @Test
    @DisplayName("재호출 시 이전 값을 덮어쓴다 (절대값 설정, forward-only 아님)")
    void setRatio_overwritesOnSecondCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoverageMetrics metrics = new CoverageMetrics(registry);

        metrics.setRatio(WatermarkSeries.DAILY_OHLCV_US, 0.9);
        metrics.setRatio(WatermarkSeries.DAILY_OHLCV_US, 0.5);

        Gauge gauge =
                registry.get(CoverageMetrics.COVERAGE_NAME)
                        .tags("series", "daily-ohlcv-us")
                        .gauge();
        assertThat(gauge.value()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("시리즈별로 독립된 시계열을 갖는다")
    void setRatio_independentPerSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CoverageMetrics metrics = new CoverageMetrics(registry);

        metrics.setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.95);

        long count =
                registry.getMeters().stream()
                        .filter(m -> CoverageMetrics.COVERAGE_NAME.equals(m.getId().getName()))
                        .count();
        assertThat(count).isEqualTo(1);
    }
}
