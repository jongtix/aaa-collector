package com.aaa.collector.market.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.kis.holiday.KisHolidayResponse.HolidayRow;
import com.aaa.collector.kis.websocket.KisMarketSchedule;
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
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketSessionGateRefresher — 일 1회 cron 갱신 (REQ-OBSV-031/032/033)")
class MarketSessionGateRefresherTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-06-19 08:10 KST (refresh cron 시각 전후) — 평일
    private static final Instant REFRESH_INSTANT = Instant.parse("2026-06-18T23:10:00Z");

    @Mock private KisHolidayClient kisHolidayClient;
    @Mock private MarketSessionGate gate;

    private MarketSessionGateRefresher makeRefresher(Clock clock) {
        return new MarketSessionGateRefresher(kisHolidayClient, gate, clock);
    }

    @Nested
    @DisplayName("정상 갱신")
    class SuccessfulRefresh {

        @Test
        @DisplayName("fetchCalendar 결과를 opnd_yn 기준으로 변환하여 gate에 전달한다")
        void refresh_normalResponse_updateCalendarWithOpndYn() {
            // Arrange
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);
            LocalDate tomorrow = LocalDate.of(2026, 6, 20);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenReturn(
                            List.of(
                                    row("20260619", "Y"),
                                    row("20260620", "N"),
                                    row("20260621", "N"),
                                    row("20260622", "Y")));

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
        @DisplayName("bass_dt 파싱 불가 행은 무시하고 나머지 행은 정상 처리한다")
        void refresh_invalidBassDt_rowSkipped() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenReturn(List.of(row("INVALID", "Y"), row("20260619", "Y")));

            makeRefresher(clock).refresh();

            ArgumentCaptor<Map<LocalDate, Boolean>> captor = ArgumentCaptor.captor();
            verify(gate).updateCalendar(captor.capture());
            assertThat(captor.getValue()).containsEntry(today, Boolean.TRUE);
            assertThat(captor.getValue()).hasSize(1);
        }

        @Test
        @DisplayName("빈 응답 — 빈 맵으로 gate.updateCalendar 호출한다")
        void refresh_emptyResponse_updateWithEmptyMap() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today)).thenReturn(List.of());

            makeRefresher(clock).refresh();

            ArgumentCaptor<Map<LocalDate, Boolean>> captor = ArgumentCaptor.captor();
            verify(gate).updateCalendar(captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("오늘 KST 날짜로 fetchCalendar를 호출한다 (ADR-009 KST 기준)")
        void refresh_callsClientWithTodayKst() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today)).thenReturn(List.of());

            makeRefresher(clock).refresh();

            verify(kisHolidayClient).fetchCalendar(today);
        }
    }

    @Nested
    @DisplayName("API 호출 실패 시 예외 격리 (REQ-OBSV-033)")
    class FailureIsolation {

        @Test
        @DisplayName("KIS API 호출 실패 — gate.updateCalendar를 호출하지 않는다 (이전 상태 유지)")
        void refresh_clientThrows_gateNotUpdated() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenThrow(new ResourceAccessException("connection refused"));

            // Act — 예외가 전파되지 않아야 한다
            makeRefresher(clock).refresh();

            // Assert — gate 상태 변경 없음
            verify(gate, never()).updateCalendar(any());
        }

        @Test
        @DisplayName("KIS 비즈니스 오류 — gate.updateCalendar를 호출하지 않는다")
        void refresh_kisApiBusinessException_gateNotUpdated() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenThrow(new KisApiBusinessException("1", "MSG001", "오류"));

            makeRefresher(clock).refresh();

            verify(gate, never()).updateCalendar(any());
        }

        @Test
        @DisplayName("예외 발생 시 메서드가 정상 반환한다 — 메트릭 노출 경로 중단 없음")
        void refresh_anyException_methodReturnsNormally() {
            Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
            LocalDate today = LocalDate.of(2026, 6, 19);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenThrow(new KisRateLimitException("alias", "rate limit"));

            // 예외 전파 없이 정상 반환 검증 (예외가 전파되면 테스트 자체가 실패)
            makeRefresher(clock).refresh();

            // gate.updateCalendar 미호출 확인 — 예외 격리 후 상태 변경 없음
            verify(gate, never()).updateCalendar(any());
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
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            MarketSessionGate realGate = new MarketSessionGate(registry, schedule, clock);

            when(kisHolidayClient.fetchCalendar(today)).thenReturn(List.of(row("20260619", "Y")));

            MarketSessionGateRefresher refresher =
                    new MarketSessionGateRefresher(kisHolidayClient, realGate, clock);

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
            LocalDate today = LocalDate.of(2026, 6, 19);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            MarketSessionGate realGate = new MarketSessionGate(registry, schedule, clock);

            when(kisHolidayClient.fetchCalendar(today))
                    .thenThrow(new ResourceAccessException("refused"));

            new MarketSessionGateRefresher(kisHolidayClient, realGate, clock).refresh();

            Gauge g = registry.get(MarketSessionGate.GATE_LAST_UPDATE_NAME).gauge();
            assertThat(g.value()).isEqualTo(0.0);
        }
    }

    /** 테스트용 HolidayRow 팩토리. */
    private static HolidayRow row(String bassDt, String opndYn) {
        return new HolidayRow(bassDt, "05", "Y", "Y", opndYn, "Y");
    }
}
