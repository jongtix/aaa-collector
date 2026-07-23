package com.aaa.collector.market.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.websocket.KisMarketSchedule;
import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.CalendarSource;
import com.aaa.collector.market.calendar.MarketCalendar;
import com.aaa.collector.market.calendar.MarketCalendarRepository;
import com.aaa.collector.observability.WatermarkProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName(
        "MarketSessionGateRefresher — 일 1회 cron 갱신 (SPEC-COLLECTOR-CALENDAR-001 TASK-007,"
                + " REQ-CAL-018/-030/-031/-036)")
class MarketSessionGateRefresherTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-06-19 08:10 KST (refresh cron 시각 전후) — 평일
    private static final Instant REFRESH_INSTANT = Instant.parse("2026-06-18T23:10:00Z");

    @Mock private MarketCalendarRepository marketCalendarRepository;
    @Mock private MarketSessionGate gate;

    private static WatermarkProperties defaultProperties() {
        return new WatermarkProperties();
    }

    private MarketSessionGateRefresher makeRefresher(Clock clock) {
        return makeRefresher(clock, defaultProperties());
    }

    private MarketSessionGateRefresher makeRefresher(Clock clock, WatermarkProperties properties) {
        return new MarketSessionGateRefresher(marketCalendarRepository, gate, clock, properties);
    }

    private static MarketCalendar row(LocalDate date, boolean open) {
        return MarketCalendar.builder()
                .calendarCode(CalendarCode.KRX)
                .calDate(date)
                .open(open)
                .source(CalendarSource.KIS_API)
                .build();
    }

    @Nested
    @DisplayName("정상 갱신")
    class SuccessfulRefresh {

        @Test
        @DisplayName("리포지토리 조회 결과를 is_open 기준으로 변환하여 gate에 전달한다")
        void refresh_normalResponse_updateCalendarWithIsOpen() {
            // Arrange
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate lowerBound = today.minusDays(14);
            LocalDate upperBound = today.plusDays(20);
            LocalDate tomorrow = LocalDate.of(2026, 6, 20);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound))
                    .thenReturn(
                            List.of(
                                    row(today, true),
                                    row(tomorrow, false),
                                    row(LocalDate.of(2026, 6, 21), false),
                                    row(LocalDate.of(2026, 6, 22), true)));

            // Act
            makeRefresher(clock).refresh();

            // Assert — gate.updateCalendar 호출 여부 + 전달된 맵 검증
            ArgumentCaptor<Map<LocalDate, Boolean>> captor = ArgumentCaptor.captor();
            verify(gate).updateCalendar(captor.capture());
            Map<LocalDate, Boolean> calendar = captor.getValue();
            assertThat(calendar.get(today)).isTrue();
            assertThat(calendar.get(tomorrow)).isFalse();
            assertThat(calendar.get(LocalDate.of(2026, 6, 21))).isFalse();
            assertThat(calendar.get(LocalDate.of(2026, 6, 22))).isTrue();
        }

        @Test
        @DisplayName("빈 조회 결과 — 빈 맵으로 gate.updateCalendar 호출한다")
        void refresh_emptyResult_updateWithEmptyMap() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), any()))
                    .thenReturn(List.of());

            makeRefresher(clock).refresh();

            ArgumentCaptor<Map<LocalDate, Boolean>> captor = ArgumentCaptor.captor();
            verify(gate).updateCalendar(captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("today − N일(N=기본 14) ~ today + 20일 범위로 조회한다 (REQ-CAL-036)")
        void refresh_queriesGateRangeWithDefaultLookback() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate lowerBound = today.minusDays(14);
            LocalDate upperBound = today.plusDays(20);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound))
                    .thenReturn(List.of());

            makeRefresher(clock).refresh();

            verify(marketCalendarRepository)
                    .findByCalendarCodeAndCalDateBetween(CalendarCode.KRX, lowerBound, upperBound);
        }

        @Test
        @DisplayName("lookback 설정값을 그대로 반영해 하한을 계산한다 (커스텀 N=7) — 상한은 항상 오늘+20일")
        void refresh_customLookback_usesConfiguredOffset() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate lowerBound = today.minusDays(7);
            LocalDate upperBound = today.plusDays(20);
            WatermarkProperties properties = defaultProperties();
            properties.setKrxCalendarLookbackDays(7);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound))
                    .thenReturn(List.of());

            makeRefresher(clock, properties).refresh();

            verify(marketCalendarRepository)
                    .findByCalendarCodeAndCalDateBetween(CalendarCode.KRX, lowerBound, upperBound);
        }
    }

    @Nested
    @DisplayName("리포지토리 조회 실패 시 예외 격리 (REQ-OBSV-033)")
    class FailureIsolation {

        @Test
        @DisplayName("리포지토리 조회 실패 — gate.updateCalendar를 호출하지 않는다 (이전 상태 유지)")
        void refresh_repositoryThrows_gateNotUpdated() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), any()))
                    .thenThrow(new QueryTimeoutException("db timeout"));

            // Act — 예외가 전파되지 않아야 한다
            makeRefresher(clock).refresh();

            // Assert — gate 상태 변경 없음
            verify(gate, never()).updateCalendar(any());
        }

        @Test
        @DisplayName("예외 발생 시 메서드가 정상 반환한다 — 메트릭 노출 경로 중단 없음")
        void refresh_anyException_methodReturnsNormally() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), any()))
                    .thenThrow(new QueryTimeoutException("db timeout"));

            // 예외 전파 없이 정상 반환 검증 (예외가 전파되면 테스트 자체가 실패)
            makeRefresher(clock).refresh();

            verify(gate, never()).updateCalendar(any());
        }
    }

    @Nested
    @DisplayName("부팅 시 즉시 refresh (REQ-WM-007, MA-01)")
    class BootRefresh {

        @Test
        @DisplayName("refreshOnStartup 호출 시 refresh()와 동일하게 게이트 범위로 조회한다")
        void refreshOnStartup_queriesGateRange() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate lowerBound = today.minusDays(14);
            LocalDate upperBound = today.plusDays(20);
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound))
                    .thenReturn(List.of());

            makeRefresher(clock).refreshOnStartup();

            verify(marketCalendarRepository)
                    .findByCalendarCodeAndCalDateBetween(CalendarCode.KRX, lowerBound, upperBound);
            verify(gate).updateCalendar(any());
        }
    }

    @Nested
    @DisplayName("게이트 갱신 통합 — gate 실제 객체와 협력 (last-update 진행 확인)")
    class GateIntegration {

        @Test
        @DisplayName("성공적인 refresh 후 gate last-update가 0이 아니다")
        void refresh_success_gateLastUpdateNonZero() {
            // Arrange — 실제 gate 객체 사용
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate lowerBound = today.minusDays(14);
            LocalDate upperBound = today.plusDays(20);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            MarketSessionGate realGate =
                    new MarketSessionGate(registry, schedule, clock, marketCalendarRepository);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound))
                    .thenReturn(List.of(row(today, true)));

            MarketSessionGateRefresher refresher =
                    new MarketSessionGateRefresher(
                            marketCalendarRepository, realGate, clock, defaultProperties());

            // Act
            refresher.refresh();

            // Assert
            Gauge g = registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge();
            assertThat(g.value()).isEqualTo((double) REFRESH_INSTANT.getEpochSecond());
        }

        @Test
        @DisplayName("실패한 refresh 후 gate last-update는 0으로 유지된다 (미갱신)")
        void refresh_failure_gateLastUpdateRemainsZero() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            MarketSessionGate realGate =
                    new MarketSessionGate(registry, schedule, clock, marketCalendarRepository);

            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), any()))
                    .thenThrow(new QueryTimeoutException("db timeout"));

            new MarketSessionGateRefresher(
                            marketCalendarRepository, realGate, clock, defaultProperties())
                    .refresh();

            Gauge g = registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }
    }
}
