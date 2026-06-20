package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BackfillMetrics — 백필 관측 메트릭 (REQ-BACKFILL-040)")
class BackfillMetricsTest {

    private static final String PROGRESS = "aaa_collector_backfill_progress_ratio";
    private static final String WINDOW_ROWS = "aaa_collector_backfill_window_rows_total";
    private static final String CLAMP_SUSPECTED = "aaa_collector_backfill_clamp_suspected_total";
    private static final String WINDOWS_TOTAL = "aaa_collector_backfill_windows_total";

    @Nested
    @DisplayName("@PostConstruct 사전 등록")
    class PostConstruct {

        @Test
        @DisplayName("initCounters() 호출 후 카운터·gauge가 0으로 사전 등록된다")
        void postConstruct_registersCountersAtZero() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            assertThat(registry.get(WINDOW_ROWS).counter().count()).isEqualTo(0.0);
            assertThat(registry.get(CLAMP_SUSPECTED).counter().count()).isEqualTo(0.0);
            assertThat(registry.get(WINDOWS_TOTAL).counter().count()).isEqualTo(0.0);
            Gauge gauge = registry.get(PROGRESS).gauge();
            assertThat(gauge.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("윈도우 실행 카운터 (recordWindow)")
    class RecordWindow {

        @Test
        @DisplayName("recordWindow 호출마다 windows_total 카운터가 1씩 증가한다")
        void recordWindow_incrementsWindowsCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordWindow(5);
            metrics.recordWindow(3);

            assertThat(registry.get(WINDOWS_TOTAL).counter().count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("rowCount > 0이면 window_rows_total 카운터가 누적된다")
        void recordWindow_accumulatesRowCount() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordWindow(5);
            metrics.recordWindow(3);

            assertThat(registry.get(WINDOW_ROWS).counter().count()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("rowCount = 0이면 window_rows_total은 증가하지 않는다")
        void recordWindow_zeroRowCount_doesNotIncrementRowsCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordWindow(0);

            assertThat(registry.get(WINDOW_ROWS).counter().count()).isEqualTo(0.0);
            // windows_total은 0 rowCount여도 증가
            assertThat(registry.get(WINDOWS_TOTAL).counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("클램프 의심 카운터 (recordClampSuspected)")
    class RecordClampSuspected {

        @Test
        @DisplayName("recordClampSuspected 호출마다 카운터가 누적된다")
        void recordClampSuspected_incrementsCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordClampSuspected();
            metrics.recordClampSuspected();

            assertThat(registry.get(CLAMP_SUSPECTED).counter().count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("진행률 gauge (recordProgress)")
    class RecordProgress {

        @Test
        @DisplayName("완료/전체 비율로 gauge가 갱신된다")
        void recordProgress_updatesGauge() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordProgress(3, 10);

            Gauge gauge = registry.get(PROGRESS).gauge();
            assertThat(gauge.value()).isEqualTo(0.3);
        }

        @Test
        @DisplayName("totalCount = 0이면 gauge = 0.0 (0 나눗셈 방지)")
        void recordProgress_totalZero_gaugeIsZero() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordProgress(0, 0);

            Gauge gauge = registry.get(PROGRESS).gauge();
            assertThat(gauge.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("recordProgress 재호출 시 gauge가 최신값으로 덮어씌워진다")
        void recordProgress_overwritesPreviousValue() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();

            metrics.recordProgress(1, 10);
            metrics.recordProgress(8, 10);

            Gauge gauge = registry.get(PROGRESS).gauge();
            assertThat(gauge.value()).isEqualTo(0.8);
        }
    }

    @Nested
    @DisplayName("per-stock 라벨 부재 (REQ-BACKFILL-040 카디널리티 억제)")
    class CardinalityControl {

        @Test
        @DisplayName("백필 메트릭에 symbol 태그가 없다")
        void noPerStockSymbolLabel() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BackfillMetrics metrics = new BackfillMetrics(registry);
            metrics.initCounters();
            metrics.recordWindow(10);
            metrics.recordClampSuspected();
            metrics.recordProgress(5, 10);

            boolean noSymbolTag =
                    registry.getMeters().stream()
                            .filter(m -> m.getId().getName().startsWith("aaa_collector_backfill_"))
                            .allMatch(m -> m.getId().getTag("symbol") == null);
            assertThat(noSymbolTag).isTrue();
        }
    }
}
