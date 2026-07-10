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

@DisplayName("ExpectedRunGaugeBinder — expected_run 게이지 (REQ-XR-001/003/005)")
class ExpectedRunGaugeBinderTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String EXPECTED_RUN = "aaa_collector_batch_expected_run_seconds";

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
