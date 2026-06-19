package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TickMetrics — 틱 종목 단위 계측 (REQ-OBSV-010/012/013)")
class TickMetricsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String COUNTER = "aaa_collector_tick_received_total";
    private static final String LAST_SEEN = "aaa_collector_tick_last_seen_seconds";

    @Nested
    @DisplayName("국내 틱 수신 시")
    class DomesticTick {

        @Test
        @DisplayName("심볼별 누적 수신 카운터가 증가한다 (label: symbol, market)")
        void incrementsReceiveCounter() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TickMetrics metrics = new TickMetrics(registry, Clock.systemDefaultZone());

            metrics.recordDomesticTick("005930");
            metrics.recordDomesticTick("005930");

            double count =
                    registry.get(COUNTER)
                            .tags("symbol", "005930", "market", "domestic")
                            .counter()
                            .count();
            assertThat(count).isEqualTo(2.0);
        }

        @Test
        @DisplayName("심볼별 last-seen gauge가 KST epoch 초로 갱신된다 (ADR-009)")
        void updatesLastSeenGaugeInKstEpochSeconds() {
            // Arrange — 고정 시계 (2026-06-19 12:00:00 KST)
            Instant fixed = Instant.parse("2026-06-19T03:00:00Z"); // 12:00 KST
            Clock clock = Clock.fixed(fixed, KST);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TickMetrics metrics = new TickMetrics(registry, clock);

            // Act
            metrics.recordDomesticTick("000660");

            // Assert — gauge가 epoch second 값을 보유
            Gauge gauge =
                    registry.get(LAST_SEEN).tags("symbol", "000660", "market", "domestic").gauge();
            assertThat(gauge.value()).isEqualTo((double) fixed.getEpochSecond());
        }

        @Test
        @DisplayName("동일 심볼 재수신 시 last-seen이 최신 시각으로 갱신된다 (무수신=stale 식별 기반)")
        void lastSeenAdvancesOnNewTick() {
            // Arrange — 두 번 호출에서 시각이 진행하는 Clock
            Instant t0 = Instant.parse("2026-06-19T03:00:00Z");
            Instant t1 = Instant.parse("2026-06-19T03:05:00Z");
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(t0, t1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TickMetrics metrics = new TickMetrics(registry, clock);

            // Act
            metrics.recordDomesticTick("005930");
            metrics.recordDomesticTick("005930");

            // Assert
            Gauge gauge =
                    registry.get(LAST_SEEN).tags("symbol", "005930", "market", "domestic").gauge();
            assertThat(gauge.value()).isEqualTo((double) t1.getEpochSecond());
        }
    }

    @Nested
    @DisplayName("카디널리티 가드 (REQ-OBSV-012)")
    class CardinalityGuard {

        @Test
        @DisplayName("동일 심볼 반복 수신은 시계열을 1개만 생성한다 (gauge·counter 각 1)")
        void singleSymbolCreatesOneSeriesEach() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TickMetrics metrics = new TickMetrics(registry, Clock.systemDefaultZone());

            for (int i = 0; i < 50; i++) {
                metrics.recordDomesticTick("005930");
            }

            long counterSeries =
                    registry.getMeters().stream()
                            .filter(m -> COUNTER.equals(m.getId().getName()))
                            .count();
            long gaugeSeries =
                    registry.getMeters().stream()
                            .filter(m -> LAST_SEEN.equals(m.getId().getName()))
                            .count();
            assertThat(counterSeries).isEqualTo(1);
            assertThat(gaugeSeries).isEqualTo(1);
        }

        @Test
        @DisplayName("모든 틱 라벨의 market는 domestic 고정 — 해외 라벨 미생성")
        void allSeriesAreDomesticMarket() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TickMetrics metrics = new TickMetrics(registry, Clock.systemDefaultZone());

            metrics.recordDomesticTick("005930");
            metrics.recordDomesticTick("000660");

            boolean allDomestic =
                    registry.getMeters().stream()
                            .filter(
                                    m ->
                                            COUNTER.equals(m.getId().getName())
                                                    || LAST_SEEN.equals(m.getId().getName()))
                            .allMatch(m -> "domestic".equals(m.getId().getTag("market")));
            assertThat(allDomestic).isTrue();
        }
    }
}
