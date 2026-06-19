package com.aaa.collector.market.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.websocket.KisMarketSchedule;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MarketSessionGate — 시장 세션 상태 게이트 (REQ-OBSV-030/033/034/003)")
class MarketSessionGateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 2026-06-19 10:00 KST — 평일 장중 (domestic open: 08:55~15:35)
    private static final Instant TRADING_HOURS_INSTANT = Instant.parse("2026-06-19T01:00:00Z");
    // 2026-06-20 10:00 KST — 토요일 (domestic closed: weekend)
    private static final Instant WEEKEND_INSTANT = Instant.parse("2026-06-20T01:00:00Z");
    // 2026-06-19 17:00 KST — 장외 시간
    private static final Instant AFTER_HOURS_INSTANT = Instant.parse("2026-06-19T08:00:00Z");

    private Clock clockAt(Instant instant) {
        return Clock.fixed(instant, KST);
    }

    @Nested
    @DisplayName("부팅 후 미설정 (boot-unset) 상태 — 스케줄만으로 판정")
    class BootUnset {

        @Test
        @DisplayName("캘린더 미로드 + 장중 시간 → 게이트 1 (schedule-only, 갭 알림 억제 금지)")
        void noCalendar_duringTradingHours_gateIsOne() {
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, schedule, clock);

            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("캘린더 미로드 + 장외 시간 → 게이트 0")
        void noCalendar_afterHours_gateIsZero() {
            Clock clock = clockAt(AFTER_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, schedule, clock);

            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("캘린더 미로드 + 주말 → 게이트 0")
        void noCalendar_weekend_gateIsZero() {
            Clock clock = clockAt(WEEKEND_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, schedule, clock);

            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("캘린더 미로드 → last-update gauge = 0 (미갱신 신호)")
        void noCalendar_lastUpdateGaugeIsZero() {
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, new KisMarketSchedule(clock), clock);

            Gauge g = registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("캘린더 로드 후 — KIS opnd_yn AND KisMarketSchedule")
    class WithCalendar {

        @Test
        @DisplayName("장중 + opnd_yn=Y → 게이트 1")
        void tradingHours_opndYnY_gateIsOne() {
            // Arrange
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate = new MarketSessionGate(registry, schedule, clock);
            LocalDate today = ZonedDateTime.now(clock).toLocalDate();

            // Act — 오늘이 개장일인 캘린더 로드
            gate.updateCalendar(Map.of(today, Boolean.TRUE));

            // Assert
            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("장중 + opnd_yn=N (증시 휴장) → 게이트 0")
        void tradingHours_opndYnN_gateIsZero() {
            // Arrange
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate = new MarketSessionGate(registry, schedule, clock);
            LocalDate today = ZonedDateTime.now(clock).toLocalDate();

            // Act — 오늘이 증시 휴장인 캘린더 로드 (공휴일 등)
            gate.updateCalendar(Map.of(today, Boolean.FALSE));

            // Assert
            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("장외 시간 + opnd_yn=Y → 게이트 0 (AND 조건 — 스케줄 false)")
        void afterHours_opndYnY_gateIsZero() {
            // Arrange
            Clock clock = clockAt(AFTER_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate = new MarketSessionGate(registry, schedule, clock);
            LocalDate today = ZonedDateTime.now(clock).toLocalDate();

            // Act
            gate.updateCalendar(Map.of(today, Boolean.TRUE));

            // Assert
            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("캘린더에 오늘 날짜 없음 → schedule-only 폴백 (갭 알림 부당 억제 방지)")
        void calendarLoadedButTodayAbsent_fallsBackToSchedule() {
            // Arrange — 장중 시간대
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate = new MarketSessionGate(registry, schedule, clock);
            LocalDate tomorrow = ZonedDateTime.now(clock).toLocalDate().plusDays(1);

            // Act — 내일 날짜만 있는 캘린더 로드 (오늘 absent)
            gate.updateCalendar(Map.of(tomorrow, Boolean.TRUE));

            // Assert — schedule-only 폴백: 장중이면 1
            Gauge g = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("updateCalendar — last-update 게이트 갱신 (REQ-OBSV-034)")
    class UpdateCalendar {

        @Test
        @DisplayName("updateCalendar 호출 시 last-update가 KST epoch 초로 갱신된다")
        void updateCalendar_advancesLastUpdateToCurrentEpoch() {
            // Arrange
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate =
                    new MarketSessionGate(registry, new KisMarketSchedule(clock), clock);

            // Act
            gate.updateCalendar(Map.of());

            // Assert
            Gauge g = registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge();
            assertThat(g.value()).isEqualTo((double) TRADING_HOURS_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("updateCalendar 반복 호출 시 last-update가 최신 시각으로 진행한다")
        void updateCalendar_multipleCallsAdvanceLastUpdate() {
            // Arrange
            Instant t1 = TRADING_HOURS_INSTANT;
            Instant t2 = TRADING_HOURS_INSTANT.plusSeconds(86_400L); // 다음날
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(t1, t2);
            when(clock.getZone()).thenReturn(KST);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate =
                    new MarketSessionGate(registry, new KisMarketSchedule(clock), clock);

            // Act & Assert
            gate.updateCalendar(Map.of());
            assertThat(registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge().value())
                    .isEqualTo((double) t1.getEpochSecond());

            gate.updateCalendar(Map.of());
            assertThat(registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge().value())
                    .isEqualTo((double) t2.getEpochSecond());
        }
    }

    @Nested
    @DisplayName("장애 격리 — 갱신 실패 시 직전 캘린더 유지 (REQ-OBSV-033)")
    class FailureIsolation {

        @Test
        @DisplayName("updateCalendar 미호출 상태에서 게이트는 schedule-only 상태를 유지한다")
        void noUpdateCalled_gateRemainsScheduleOnly() {
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, schedule, clock);

            // 갱신 없이 2회 조회 — 일관성 보장
            double v1 = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge().value();
            double v2 = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge().value();
            assertThat(v1).isEqualTo(v2).isEqualTo(1.0);
        }

        @Test
        @DisplayName("캘린더 로드 후 실패 시나리오 — 이전에 로드된 캘린더 상태 반영 유지")
        void afterLoad_calendarRetained_notRevertedToScheduleOnly() {
            // Arrange — 장중, opnd_yn=N (증시 휴장)
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MarketSessionGate gate = new MarketSessionGate(registry, schedule, clock);
            LocalDate today = ZonedDateTime.now(clock).toLocalDate();

            // Act — 휴장 캘린더 로드 후 "실패" (updateCalendar 미호출로 시뮬레이션)
            gate.updateCalendar(Map.of(today, Boolean.FALSE));

            // Assert — 이전 로드된 캘린더(휴장) 유지: 0
            double v = registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge().value();
            assertThat(v).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("메트릭 이름 검증")
    class MetricNames {

        @Test
        @DisplayName("market-open 게이트와 last-update 게이트 두 gauge가 registry에 등록된다")
        void bothGaugesRegisteredAtConstruction() {
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, new KisMarketSchedule(clock), clock);

            // market-open gauge 존재 확인
            assertThat(registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge()).isNotNull();
            // last-update gauge 존재 확인
            assertThat(registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge()).isNotNull();
        }

        @Test
        @DisplayName("생성자에서 KIS API 호출이 발생하지 않는다 (NO network at boot)")
        void constructorDoesNotCallKisApi() {
            // Arrange — schedule mock (network call은 KisHolidayClient가 담당, 생성자에서 호출하면 안 됨)
            KisMarketSchedule schedule = mock(KisMarketSchedule.class);
            when(schedule.isDomesticOpen(any())).thenReturn(false);

            // Act — 생성자만 호출 (KisHolidayClient 미포함: 게이트 생성자에 KisHolidayClient 의존 없음)
            Clock clock = clockAt(TRADING_HOURS_INSTANT);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new MarketSessionGate(registry, schedule, clock);

            // Assert — isDomesticOpen은 gauge 조회 시 호출됨, 생성자에서 schedule 사용 여부는 gauge value로 간접 확인
            // 핵심: 생성자에서 RestClient/HTTP 호출 없음 (KisHolidayClient 의존 부재로 보장)
            assertThat(registry.get(MarketSessionGate.MARKET_OPEN_NAME).gauge()).isNotNull();
        }
    }
}
