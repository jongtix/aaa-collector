package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoverageMetrics — 데이터 커버리지 게이지 (REQ-WM-010/011, REQ-WSR4-001)")
class CoverageMetricsTest {

    @Mock private CoverageRatioRepository coverageRatioRepository;

    private CoverageMetrics metrics(SimpleMeterRegistry registry) {
        return new CoverageMetrics(registry, coverageRatioRepository);
    }

    private Gauge gauge(SimpleMeterRegistry registry, String seriesLabel) {
        return registry.get(CoverageMetrics.COVERAGE_NAME).tags("series", seriesLabel).gauge();
    }

    @Nested
    @DisplayName("setRatio — 게이지 설정 + Redis write-through")
    class SetRatio {

        @Test
        @DisplayName("setRatio 호출 시 series 라벨로 게이지가 등록되고 값이 설정된다")
        void setRatio_registersGaugeWithSeriesTag() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.955);

            assertThat(gauge(registry, "daily-ohlcv-krx").value()).isEqualTo(0.955);
        }

        @Test
        @DisplayName("재호출 시 이전 값을 덮어쓴다 (절대값 설정, forward-only 아님)")
        void setRatio_overwritesOnSecondCall() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CoverageMetrics metrics = metrics(registry);

            metrics.setRatio(WatermarkSeries.DAILY_OHLCV_US, 0.9);
            metrics.setRatio(WatermarkSeries.DAILY_OHLCV_US, 0.5);

            assertThat(gauge(registry, "daily-ohlcv-us").value()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("setRatio 호출 시 Redis에 series 라벨로 비율이 best-effort 기록된다 (REQ-WSR4-001)")
        void setRatio_persistsToRedis() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.72);

            verify(coverageRatioRepository).save("daily-ohlcv-krx", 0.72);
        }

        @Test
        @DisplayName("Redis 기록이 DataAccessException으로 실패해도 게이지는 설정되고 예외가 전파되지 않는다")
        void setRatio_whenRedisFails_stillSetsGaugeAndSwallows() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            doThrow(new QueryTimeoutException("Redis 오류"))
                    .when(coverageRatioRepository)
                    .save("daily-ohlcv-us", 0.33);
            CoverageMetrics metrics = metrics(registry);

            assertThatNoException()
                    .isThrownBy(() -> metrics.setRatio(WatermarkSeries.DAILY_OHLCV_US, 0.33));
            assertThat(gauge(registry, "daily-ohlcv-us").value()).isEqualTo(0.33);
        }
    }

    @Nested
    @DisplayName("warmRatio — 웜스타트 전용(게이지만, Redis 재기록 없음)")
    class WarmRatio {

        @Test
        @DisplayName("warmRatio는 게이지를 설정하되 Redis에 다시 쓰지 않는다 (REQ-WSR4-001)")
        void warmRatio_setsGaugeWithoutPersist() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).warmRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.88);

            assertThat(gauge(registry, "daily-ohlcv-krx").value()).isEqualTo(0.88);
            verify(coverageRatioRepository, never()).save("daily-ohlcv-krx", 0.88);
        }
    }

    @Nested
    @DisplayName("initGauges — 부팅 시 KRX/US 사전 등록 (REQ-WSR4-001)")
    class InitGauges {

        @Test
        @DisplayName("initGauges 호출 시 KRX/US 두 라벨이 0.0으로 사전 등록된다")
        void initGauges_preRegistersBothSeriesAtZero() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CoverageMetrics metrics = metrics(registry);

            metrics.initGauges();

            assertThat(gauge(registry, "daily-ohlcv-krx").value()).isEqualTo(0.0);
            assertThat(gauge(registry, "daily-ohlcv-us").value()).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("시리즈별로 독립된 시계열을 갖는다")
    void setRatio_independentPerSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        metrics(registry).setRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.95);

        long count =
                registry.getMeters().stream()
                        .filter(m -> CoverageMetrics.COVERAGE_NAME.equals(m.getId().getName()))
                        .count();
        assertThat(count).isEqualTo(1);
    }
}
