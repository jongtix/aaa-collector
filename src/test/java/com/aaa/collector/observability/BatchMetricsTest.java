package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BatchMetrics — 배치 집계 계측 (REQ-OBSV-020/021/022/023)")
class BatchMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TARGET = "aaa_collector_batch_target_total";
    private static final String SUCCESS = "aaa_collector_batch_success_total";
    private static final String FAIL = "aaa_collector_batch_fail_total";
    private static final String SKIP = "aaa_collector_batch_skip_total";
    private static final String COMPLETENESS = "aaa_collector_batch_completeness_ratio";
    private static final String LAST_LOAD = "aaa_collector_batch_last_load_seconds";
    private static final String SILENT_DROP = "aaa_collector_batch_silent_drops_total";
    private static final String PARSE_REJECT = "aaa_collector_batch_parse_rejections_total";

    @Nested
    @DisplayName("배치 회차 완료 기록 (REQ-OBSV-020)")
    class RecordCompletion {

        @Test
        @DisplayName("대상/성공/실패/스킵 카운터가 배치 라벨별로 누적된다")
        void accumulatesCountersByBatchLabel() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordCompletion("domestic-daily", 100, 90, 5, 5);

            assertThat(counter(registry, TARGET, "domestic-daily")).isEqualTo(100.0);
            assertThat(counter(registry, SUCCESS, "domestic-daily")).isEqualTo(90.0);
            assertThat(counter(registry, FAIL, "domestic-daily")).isEqualTo(5.0);
            assertThat(counter(registry, SKIP, "domestic-daily")).isEqualTo(5.0);
        }

        @Test
        @DisplayName("충족률 gauge = 성공/대상 (REQ-OBSV-020)")
        void completenessRatioIsSuccessOverTarget() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordCompletion("domestic-daily", 200, 150, 30, 20);

            Gauge gauge = registry.get(COMPLETENESS).tags("batch", "domestic-daily").gauge();
            assertThat(gauge.value()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("대상 0건이면 충족률은 0으로 노출된다 (0 나눗셈 방지)")
        void completenessRatioIsZeroWhenNoTarget() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordCompletion("domestic-daily", 0, 0, 0, 0);

            Gauge gauge = registry.get(COMPLETENESS).tags("batch", "domestic-daily").gauge();
            assertThat(gauge.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("배치 라벨별로 시계열이 분리된다 (3종 수급 라벨 등)")
        void seriesSeparatedByBatchLabel() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordCompletion("domestic-supply-investor", 10, 10, 0, 0);
            metrics.recordCompletion("domestic-supply-short-sale", 10, 8, 1, 1);

            assertThat(counter(registry, SUCCESS, "domestic-supply-investor")).isEqualTo(10.0);
            assertThat(counter(registry, SUCCESS, "domestic-supply-short-sale")).isEqualTo(8.0);
        }

        @Test
        @DisplayName("per-stock 종목 라벨을 생성하지 않는다 (REQ-OBSV-022 — symbol 태그 부재)")
        void noPerStockSymbolLabel() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordCompletion("domestic-daily", 100, 100, 0, 0);

            boolean noSymbolTag =
                    registry.getMeters().stream()
                            .filter(m -> m.getId().getName().startsWith("aaa_collector_batch_"))
                            .allMatch(m -> m.getId().getTag("symbol") == null);
            assertThat(noSymbolTag).isTrue();
        }
    }

    @Nested
    @DisplayName("마지막 적재 시각 (REQ-OBSV-021)")
    class LastLoad {

        @Test
        @DisplayName("성공 적재 기록 시 last-load gauge가 KST epoch 초로 갱신된다 (ADR-009)")
        void recordsLastLoadInKstEpochSeconds() {
            Instant fixed = Instant.parse("2026-06-19T07:00:00Z"); // 16:00 KST
            Clock clock = Clock.fixed(fixed, KST);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, clock);

            metrics.recordCompletion("domestic-daily", 100, 90, 5, 5);

            Gauge gauge = registry.get(LAST_LOAD).tags("batch", "domestic-daily").gauge();
            assertThat(gauge.value()).isEqualTo((double) fixed.getEpochSecond());
        }
    }

    @Nested
    @DisplayName("침묵 드롭 warning 카운터 (REQ-OBSV-023, DP4 단일 카운터)")
    class SilentDrop {

        @Test
        @DisplayName("단일 누적 카운터로 침묵 드롭 건수를 증가시킨다 (사유별 라벨 없음)")
        void incrementsSingleSilentDropCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordSilentDrops(3);
            metrics.recordSilentDrops(2);

            assertThat(registry.get(SILENT_DROP).counter().count()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("0건 드롭은 카운터를 증가시키지 않는다")
        void zeroDropsDoesNotIncrement() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordSilentDrops(0);

            assertThat(registry.get(SILENT_DROP).counter().count()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("파싱 거부 카운터 (REQ-SSD-009 — last_load 독립)")
    class ParseRejections {

        @Test
        @DisplayName("배치 라벨별로 파싱 거부 건수를 누적한다")
        void accumulatesByBatchLabel() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordParseRejections("overseas-shortsale-backfill", 3);
            metrics.recordParseRejections("overseas-shortsale-backfill", 2);
            metrics.recordParseRejections("overseas-shortsale-daily", 1);

            assertThat(counter(registry, PARSE_REJECT, "overseas-shortsale-backfill"))
                    .isEqualTo(5.0);
            assertThat(counter(registry, PARSE_REJECT, "overseas-shortsale-daily")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("0건 호출도 시계열을 0으로 등록한다 (계측 연결 노출)")
        void zeroRegistersSeries() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordParseRejections("overseas-shortsale-backfill", 0);

            assertThat(counter(registry, PARSE_REJECT, "overseas-shortsale-backfill"))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("파싱 거부 카운터는 last_load gauge를 갱신하지 않는다 (독립 시계열)")
        void doesNotTouchLastLoad() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.recordParseRejections("overseas-shortsale-backfill", 4);

            boolean noLastLoad =
                    registry.getMeters().stream()
                            .noneMatch(m -> LAST_LOAD.equals(m.getId().getName()));
            assertThat(noLastLoad).isTrue();
        }
    }

    @Nested
    @DisplayName("warm-start API (REQ-001/002/003)")
    class WarmStart {

        @Test
        @DisplayName("warmLastLoad 호출 시 last_load_seconds gauge가 instant의 epochSecond로 set된다")
        void setsLastLoadGaugeToEpochSecond() {
            Instant instant = Instant.parse("2026-06-24T01:00:00Z"); // 10:00 KST
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.warmLastLoad("domestic-daily", instant);

            Gauge gauge = registry.get(LAST_LOAD).tags("batch", "domestic-daily").gauge();
            assertThat(gauge.value()).isEqualTo((double) instant.getEpochSecond());
        }

        @Test
        @DisplayName("warmLastLoad는 completeness gauge와 카운터를 변경하지 않는다")
        void doesNotTouchCompletenessOrCounters() {
            Instant instant = Instant.parse("2026-06-24T01:00:00Z");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.warmLastLoad("domestic-daily", instant);

            // Arrange: completeness gauge and counters should not exist after warm-start only
            Set<String> counterNames = Set.of(TARGET, SUCCESS, FAIL, SKIP);
            boolean noCompleteness =
                    registry.getMeters().stream()
                            .noneMatch(m -> COMPLETENESS.equals(m.getId().getName()));
            boolean noCounters =
                    registry.getMeters().stream()
                            .map(m -> m.getId().getName())
                            .noneMatch(counterNames::contains);
            assertThat(noCompleteness).isTrue();
            assertThat(noCounters).isTrue();
        }

        @Test
        @DisplayName("warmLastLoad 재호출 시 새 값으로 덮어쓴다 (멱등)")
        void overwritesWithNewValueOnSecondCall() {
            Instant first = Instant.parse("2026-06-24T01:00:00Z");
            Instant second = Instant.parse("2026-06-24T07:00:00Z");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.warmLastLoad("domestic-daily", first);
            metrics.warmLastLoad("domestic-daily", second);

            Gauge gauge = registry.get(LAST_LOAD).tags("batch", "domestic-daily").gauge();
            assertThat(gauge.value()).isEqualTo((double) second.getEpochSecond());
        }

        @Test
        @DisplayName("warmLastLoad는 신규 메트릭 이름을 등록하지 않는다")
        void doesNotRegisterNewMetricNames() {
            Instant instant = Instant.parse("2026-06-24T01:00:00Z");
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());

            metrics.warmLastLoad("domestic-daily", instant);

            Set<String> knownNames =
                    Set.of(
                            TARGET,
                            SUCCESS,
                            FAIL,
                            SKIP,
                            COMPLETENESS,
                            LAST_LOAD,
                            SILENT_DROP,
                            PARSE_REJECT);
            boolean onlyKnownNames =
                    registry.getMeters().stream()
                            .map(m -> m.getId().getName())
                            .filter(name -> name.startsWith("aaa_collector_batch_"))
                            .allMatch(knownNames::contains);
            assertThat(onlyKnownNames).isTrue();
        }
    }

    private static double counter(SimpleMeterRegistry registry, String name, String batch) {
        return registry.get(name).tags("batch", batch).counter().count();
    }
}
