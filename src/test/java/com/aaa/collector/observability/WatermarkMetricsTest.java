package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WatermarkMetrics — 데이터 워터마크 게이지 (REQ-WM-001~005)")
class WatermarkMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String WATERMARK = "aaa_collector_data_watermark_seconds";

    private SimpleMeterRegistry registry;
    private WatermarkMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WatermarkMetrics(registry);
        metrics.initGauges();
    }

    @Nested
    @DisplayName("부팅 시 사전 등록 (REQ-WM-003, absent 방지)")
    class InitGauges {

        @Test
        @DisplayName("17개 시리즈 전부가 0으로 사전 등록된다")
        void registersAllSeventeenSeriesAtZero() {
            long count =
                    registry.getMeters().stream()
                            .filter(m -> WATERMARK.equals(m.getId().getName()))
                            .count();

            assertThat(count).isEqualTo(WatermarkSeries.values().length);
            assertThat(count).isEqualTo(17);
        }

        @Test
        @DisplayName("각 게이지는 series/market 태그를 사전값 그대로 노출한다")
        void exposesSeriesAndMarketTags() {
            Gauge gauge =
                    registry.get(WATERMARK)
                            .tags("series", "daily-ohlcv-krx", "market", "domestic")
                            .gauge();

            assertThat(gauge.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("forward-only 갱신 (REQ-WM-001)")
    class Advance {

        @Test
        @DisplayName("최초 advance 시 KST 자정 epoch 초로 갱신된다")
        void advancesToKstMidnightEpoch() {
            LocalDate date = LocalDate.of(2026, 7, 3);
            long expected = date.atStartOfDay(KST).toEpochSecond();

            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, date);

            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo((double) expected);
        }

        @Test
        @DisplayName("더 이른 날짜로 advance해도 후퇴하지 않는다 (백필 안전성)")
        void doesNotRegressOnEarlierDate() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));
            double advanced = gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX);

            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 6, 1));

            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo(advanced);
        }

        @Test
        @DisplayName("더 늦은 날짜로 advance하면 전진한다")
        void advancesForwardOnLaterDate() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 6, 1));

            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));

            long expected = LocalDate.of(2026, 7, 3).atStartOfDay(KST).toEpochSecond();
            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo((double) expected);
        }

        @Test
        @DisplayName("null 날짜는 무동작이다")
        void nullDateIsNoOp() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));
            double advanced = gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX);

            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, null);

            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo(advanced);
        }

        @Test
        @DisplayName("시리즈별로 독립된 시계열을 갱신한다")
        void advancesIndependentlyPerSeries() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));

            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_US)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("절대값 재동기화 (REQ-WM-003/004)")
    class Resync {

        @Test
        @DisplayName("resync는 현재 값보다 낮은 날짜로도 후퇴시킨다 (TRUNCATE 자가치유)")
        void resyncCanRegress() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));

            metrics.resync(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 6, 1));

            long expected = LocalDate.of(2026, 6, 1).atStartOfDay(KST).toEpochSecond();
            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo((double) expected);
        }

        @Test
        @DisplayName("resync에 null을 전달하면 0으로 설정된다 (빈 테이블)")
        void resyncNullSetsZero() {
            metrics.advance(WatermarkSeries.DAILY_OHLCV_KRX, LocalDate.of(2026, 7, 3));

            metrics.resync(WatermarkSeries.DAILY_OHLCV_KRX, null);

            assertThat(gaugeValue(WatermarkSeries.DAILY_OHLCV_KRX)).isEqualTo(0.0);
        }
    }

    private double gaugeValue(WatermarkSeries series) {
        return registry.get(WATERMARK)
                .tags("series", series.seriesLabel(), "market", series.market())
                .gauge()
                .value();
    }
}
