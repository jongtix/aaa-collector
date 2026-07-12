package com.aaa.collector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.schedule.catchup.ExpectedFireCalculator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("ExpectedRunGaugeBinder — expected_run/run_margin/enrolled 게이지 + 부팅 eager 등록")
class ExpectedRunGaugeBinderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EXPECTED_RUN = "aaa_collector_batch_expected_run_seconds";
    private static final String RUN_MARGIN = "aaa_collector_batch_run_margin_seconds";
    private static final String ENROLLED = "aaa_collector_batch_enrolled";

    @Test
    @DisplayName("레지스트리 전 엔트리에 대해 expected_run 게이지를 등록한다 (REQ-XR-001)")
    void registersGaugeForEveryRegistryEntry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedRunGaugeBinder binder =
                new ExpectedRunGaugeBinder(
                        registry,
                        Clock.systemDefaultZone(),
                        runRegistry,
                        new ExpectedFireCalculator());

        binder.registerExpectedRunGauges();

        for (BatchRunEntry entry : runRegistry.entries()) {
            Gauge gauge = registry.find(EXPECTED_RUN).tags("batch", entry.label()).gauge();
            assertThat(gauge).as("expected_run gauge for batch=%s", entry.label()).isNotNull();
        }
        long seriesCount =
                registry.getMeters().stream()
                        .filter(m -> EXPECTED_RUN.equals(m.getId().getName()))
                        .count();
        assertThat(seriesCount).isEqualTo(runRegistry.entries().size());
    }

    @Test
    @DisplayName("게이지 값 = ExpectedFireCalculator가 산출한 직전 발화 epoch (REQ-XR-003)")
    void gaugeValueMatchesExpectedFireCalculator() {
        // Arrange: 수요일 20:00 KST — MON-FRI 16:00 KST cron의 직전 발화는 같은 날 16:00 KST 슬롯.
        Instant now = Instant.parse("2026-07-08T11:00:00Z"); // 수 20:00 KST
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedFireCalculator calculator = new ExpectedFireCalculator();
        ExpectedRunGaugeBinder binder =
                new ExpectedRunGaugeBinder(
                        registry, Clock.fixed(now, KST), runRegistry, calculator);
        binder.registerExpectedRunGauges();

        // Act
        BatchRunEntry investor = entryByLabel(runRegistry, "domestic-supply-investor");
        double actual = registry.get(EXPECTED_RUN).tags("batch", investor.label()).gauge().value();

        // Assert: 게이지가 계산기 반환값을 그대로 노출한다.
        double expected =
                calculator
                        .calculate(investor.cron(), investor.zone(), now)
                        .orElseThrow()
                        .getEpochSecond();
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("토요일 계산 시 직전 금요일 슬롯 값에 고정된다 (REQ-XR-005 주말 무전진)")
    void weekendPinsToFridaySlot() {
        // Arrange: 토요일 12:00 KST. MON-FRI 16:00 KST cron의 직전 발화는 금요일 16:00 KST 슬롯이어야 한다.
        Instant saturday = Instant.parse("2026-07-11T03:00:00Z"); // 토 12:00 KST
        Instant fridaySlot = Instant.parse("2026-07-10T07:00:00Z"); // 금 16:00 KST
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExpectedRunGaugeBinder binder =
                new ExpectedRunGaugeBinder(
                        registry,
                        Clock.fixed(saturday, KST),
                        defaultRunRegistry(),
                        new ExpectedFireCalculator());
        binder.registerExpectedRunGauges();

        // Act
        double actual =
                registry.get(EXPECTED_RUN)
                        .tags("batch", "domestic-supply-investor")
                        .gauge()
                        .value();

        // Assert
        assertThat(actual).isEqualTo((double) fridaySlot.getEpochSecond());
    }

    @Test
    @DisplayName("매 조회 시 상태 없이 재계산된다 — 시각이 전진하면 값도 다음 슬롯으로 갱신된다 (REQ-XR-001 stateless)")
    void recomputesStatelesslyOnEachScrape() {
        // Arrange: 시각을 바꿀 수 있는 clock으로 두 번 조회한다.
        MutableClock clock =
                new MutableClock(Instant.parse("2026-07-08T11:00:00Z"), KST); // 수 20:00 KST
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExpectedRunGaugeBinder binder =
                new ExpectedRunGaugeBinder(
                        registry, clock, defaultRunRegistry(), new ExpectedFireCalculator());
        binder.registerExpectedRunGauges();
        Gauge gauge = registry.get(EXPECTED_RUN).tags("batch", "domestic-supply-investor").gauge();

        // Act: 수요일 슬롯 조회 → 목요일로 전진 후 재조회.
        double wednesdayValue = gauge.value();
        clock.setInstant(Instant.parse("2026-07-09T11:00:00Z")); // 목 20:00 KST
        double thursdayValue = gauge.value();

        // Assert: 캐시되지 않고 다음 슬롯으로 재계산된다.
        assertThat(thursdayValue).isGreaterThan(wednesdayValue);
        assertThat(thursdayValue)
                .isEqualTo((double) Instant.parse("2026-07-09T07:00:00Z").getEpochSecond());
    }

    @Test
    @DisplayName("각 편입 배치에 run_margin 게이지를 레지스트리 마진 값(초)으로 노출한다 (REQ-XR-006)")
    void registersRunMarginGaugeForEveryEntry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedRunGaugeBinder binder = newBinder(registry, Clock.systemDefaultZone(), runRegistry);

        binder.registerGauges();

        for (BatchRunEntry entry : runRegistry.entries()) {
            Gauge gauge = registry.find(RUN_MARGIN).tags("batch", entry.label()).gauge();
            assertThat(gauge).as("run_margin gauge for batch=%s", entry.label()).isNotNull();
            assertThat(gauge.value())
                    .as("run_margin value for batch=%s", entry.label())
                    .isEqualTo((double) entry.marginSeconds());
        }
        long seriesCount = seriesCount(registry, RUN_MARGIN);
        assertThat(seriesCount).isEqualTo(runRegistry.entries().size());
    }

    @Test
    @DisplayName("enrolled(라벨 없음)을 레지스트리 엔트리 개수로 노출한다 (REQ-XR-007)")
    void exposesEnrolledAsRegistrySize() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedRunGaugeBinder binder = newBinder(registry, Clock.systemDefaultZone(), runRegistry);

        binder.registerGauges();

        Gauge gauge = registry.find(ENROLLED).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo((double) runRegistry.entries().size());
        // 무라벨 단일 시계열 — batch 라벨이 붙지 않는다.
        assertThat(gauge.getId().getTag("batch")).isNull();
        assertThat(seriesCount(registry, ENROLLED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("부팅 직후(cron 발화 이전) 전 게이지 시계열이 이미 존재한다 — eager 전수 등록 (REQ-XR-031)")
    void eagerlyRegistersAllSeriesAtBoot() {
        // Arrange: 빈 레지스트리 — 어떤 cron도 발화하지 않았고 어떤 완료 이벤트도 없는 부팅 직후 상태.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedRunGaugeBinder binder = newBinder(registry, Clock.systemDefaultZone(), runRegistry);

        // Act: @PostConstruct 진입점(빈 초기화 시점)만 호출 — scrape·완료 touch 없이.
        binder.registerGauges();

        // Assert: lazy였다면 발화 전 부재일 시계열이 전부 즉시 존재한다.
        int enrolled = runRegistry.entries().size();
        assertThat(seriesCount(registry, EXPECTED_RUN))
                .as("expected_run 시계열 eager 전수 등록")
                .isEqualTo(enrolled);
        assertThat(seriesCount(registry, RUN_MARGIN))
                .as("run_margin 시계열 eager 전수 등록")
                .isEqualTo(enrolled);
        assertThat(seriesCount(registry, ENROLLED)).as("enrolled 시계열 eager 등록").isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "PrometheusMeterRegistry 노출명이 등록 상수와 정확히 일치한다 — _total 접미사 자동 제거 회귀 가드"
                    + " (SPEC-COLLECTOR-EXPECTED-RUN-001 게이트/데드맨 이름 불일치 사고 재발 방지)")
    void prometheusExposedNamesMatchRegisteredConstantsExactly() {
        // Arrange: SimpleMeterRegistry는 PrometheusNamingConvention을 거치지 않아 _total 접미사 제거를
        // 재현하지 못한다 — 실제 /actuator/prometheus 경로와 동일한 PrometheusMeterRegistry로 검증해야 한다.
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry registry =
                new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                        io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        BatchRunRegistry runRegistry = defaultRunRegistry();
        ExpectedRunGaugeBinder binder =
                new ExpectedRunGaugeBinder(
                        registry,
                        Clock.systemDefaultZone(),
                        runRegistry,
                        new ExpectedFireCalculator());

        // Act
        binder.registerGauges();
        String scraped = registry.scrape();

        // Assert: 등록에 쓴 상수 이름 그대로가 스크레이프 출력에 나타나야 한다.
        assertThat(scraped).as("expected_run 노출명 불변").contains(EXPECTED_RUN + "{");
        assertThat(scraped).as("run_margin 노출명 불변").contains(RUN_MARGIN + "{");
        assertThat(scraped)
                .as("enrolled 노출명 불변 — _total 접미사가 있었다면 여기서 실패했어야 함")
                .containsPattern(java.util.regex.Pattern.compile("(?m)^" + ENROLLED + " \\d"));
        assertThat(scraped).as("_total 접미사 재도입 금지").doesNotContain(ENROLLED + "_total");
    }

    private static ExpectedRunGaugeBinder newBinder(
            SimpleMeterRegistry registry, Clock clock, BatchRunRegistry runRegistry) {
        return new ExpectedRunGaugeBinder(
                registry, clock, runRegistry, new ExpectedFireCalculator());
    }

    private static long seriesCount(SimpleMeterRegistry registry, String name) {
        return registry.getMeters().stream().filter(m -> name.equals(m.getId().getName())).count();
    }

    private static BatchRunRegistry defaultRunRegistry() {
        return new BatchRunRegistry(new StandardEnvironment());
    }

    private static BatchRunEntry entryByLabel(BatchRunRegistry runRegistry, String label) {
        return runRegistry.entries().stream()
                .filter(e -> e.label().equals(label))
                .findFirst()
                .orElseThrow();
    }

    /** 조회마다 서로 다른 시각을 주입해 게이지 stateless 재계산을 검증하기 위한 가변 clock. */
    private static final class MutableClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        MutableClock(Instant currentInstant, ZoneId zone) {
            super();
            this.currentInstant = currentInstant;
            this.zone = zone;
        }

        void setInstant(Instant currentInstant) {
            this.currentInstant = currentInstant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
