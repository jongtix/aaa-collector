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
@DisplayName("BackfillDensityMetrics — 밀도 게이지 A/B (REQ-BACKFILL-154/-155/-156, REQ-WSR4-002)")
class BackfillDensityMetricsTest {

    @Mock private BackfillDensityRepository backfillDensityRepository;

    private BackfillDensityMetrics metrics(SimpleMeterRegistry registry) {
        return new BackfillDensityMetrics(registry, backfillDensityRepository);
    }

    private Gauge gauge(SimpleMeterRegistry registry, String name, String seriesLabel) {
        return registry.get(name).tags("series", seriesLabel).gauge();
    }

    @Nested
    @DisplayName("initGauges — 부팅 시 KRX/US 두 게이지 사전 등록 (REQ-156)")
    class InitGauges {

        @Test
        @DisplayName("initGauges 호출 시 A/B × KRX/US 4개 시계열이 0으로 사전 등록된다")
        void initGauges_preRegistersFourSeriesAtZero() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillDensityMetrics metrics = metrics(registry);

            metrics.initGauges();

            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.BELOW_FLOOR_NAME,
                                            "daily-ohlcv-krx")
                                    .value())
                    .isEqualTo(0.0);
            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.BELOW_FLOOR_NAME,
                                            "daily-ohlcv-us")
                                    .value())
                    .isEqualTo(0.0);
            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.INTERNAL_GAP_NAME,
                                            "daily-ohlcv-krx")
                                    .value())
                    .isEqualTo(0.0);
            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.INTERNAL_GAP_NAME,
                                            "daily-ohlcv-us")
                                    .value())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("setBelowFloorCount — 게이지 A 설정 + Redis write-through")
    class SetBelowFloor {

        @Test
        @DisplayName("게이지 A가 series/market 라벨로 설정된다")
        void setsGaugeWithSeriesAndMarketTags() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 3L);

            Gauge gauge =
                    registry.get(BackfillDensityMetrics.BELOW_FLOOR_NAME)
                            .tags("series", "daily-ohlcv-krx", "market", "domestic")
                            .gauge();
            assertThat(gauge.value()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("setBelowFloorCount 시 Redis에 best-effort 기록된다 (REQ-WSR4-002)")
        void persistsToRedis() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_US, 7L);

            verify(backfillDensityRepository).saveBelowFloor("daily-ohlcv-us", 7L);
        }

        @Test
        @DisplayName("Redis 기록이 DataAccessException으로 실패해도 게이지는 설정되고 예외가 전파되지 않는다")
        void whenRedisFails_stillSetsGaugeAndSwallows() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            doThrow(new QueryTimeoutException("Redis 오류"))
                    .when(backfillDensityRepository)
                    .saveBelowFloor("daily-ohlcv-krx", 2L);
            BackfillDensityMetrics metrics = metrics(registry);

            assertThatNoException()
                    .isThrownBy(
                            () -> metrics.setBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 2L));
            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.BELOW_FLOOR_NAME,
                                            "daily-ohlcv-krx")
                                    .value())
                    .isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("setInternalGapCount — 게이지 B 설정 + Redis write-through")
    class SetInternalGap {

        @Test
        @DisplayName("게이지 B가 설정되고 Redis에 best-effort 기록된다 (REQ-WSR4-002)")
        void setsGaugeAndPersists() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).setInternalGapCount(WatermarkSeries.DAILY_OHLCV_US, 5L);

            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.INTERNAL_GAP_NAME,
                                            "daily-ohlcv-us")
                                    .value())
                    .isEqualTo(5.0);
            verify(backfillDensityRepository).saveInternalGap("daily-ohlcv-us", 5L);
        }

        @Test
        @DisplayName("Redis 기록 실패 시 게이지는 설정되고 예외가 전파되지 않는다")
        void whenRedisFails_stillSetsGaugeAndSwallows() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            doThrow(new QueryTimeoutException("Redis 오류"))
                    .when(backfillDensityRepository)
                    .saveInternalGap("daily-ohlcv-krx", 4L);
            BackfillDensityMetrics metrics = metrics(registry);

            assertThatNoException()
                    .isThrownBy(
                            () -> metrics.setInternalGapCount(WatermarkSeries.DAILY_OHLCV_KRX, 4L));
            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.INTERNAL_GAP_NAME,
                                            "daily-ohlcv-krx")
                                    .value())
                    .isEqualTo(4.0);
        }
    }

    @Nested
    @DisplayName("warm* — 웜스타트 전용(게이지만, Redis 재기록 없음)")
    class WarmMethods {

        @Test
        @DisplayName("warmBelowFloorCount는 게이지를 설정하되 Redis에 다시 쓰지 않는다")
        void warmBelowFloor_setsGaugeWithoutPersist() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).warmBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 9L);

            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.BELOW_FLOOR_NAME,
                                            "daily-ohlcv-krx")
                                    .value())
                    .isEqualTo(9.0);
            verify(backfillDensityRepository, never()).saveBelowFloor("daily-ohlcv-krx", 9L);
        }

        @Test
        @DisplayName("warmInternalGapCount는 게이지를 설정하되 Redis에 다시 쓰지 않는다")
        void warmInternalGap_setsGaugeWithoutPersist() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            metrics(registry).warmInternalGapCount(WatermarkSeries.DAILY_OHLCV_US, 11L);

            assertThat(
                            gauge(
                                            registry,
                                            BackfillDensityMetrics.INTERNAL_GAP_NAME,
                                            "daily-ohlcv-us")
                                    .value())
                    .isEqualTo(11.0);
            verify(backfillDensityRepository, never()).saveInternalGap("daily-ohlcv-us", 11L);
        }
    }
}
