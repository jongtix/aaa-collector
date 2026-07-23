package com.aaa.collector.market.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.kis.holiday.KisHolidayResponse.HolidayRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link MarketCalendarRefreshScheduler} KRX 일일 갱신 단위 테스트 (SPEC-COLLECTOR-CALENDAR-001 TASK-005,
 * AC-4/AC-6/AC-7/AC-8/AC-9).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketCalendarRefreshScheduler — KRX 일일 갱신 (테이블 신선도 전용, D10)")
class MarketCalendarRefreshSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // 2026-07-20(월) 08:10 KST
    private static final Instant REFRESH_INSTANT = Instant.parse("2026-07-19T23:10:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 20);

    @Mock private MarketCalendarRepository marketCalendarRepository;
    @Mock private MarketCalendarService marketCalendarService;
    @Mock private KisHolidayClient kisHolidayClient;

    private MarketCalendarRefreshScheduler makeScheduler(SimpleMeterRegistry registry) {
        Clock clock = Clock.fixed(REFRESH_INSTANT, KST);
        MarketCalendarRefreshScheduler scheduler =
                new MarketCalendarRefreshScheduler(
                        marketCalendarRepository,
                        marketCalendarService,
                        kisHolidayClient,
                        registry,
                        clock);
        scheduler.initAlertCounters();
        return scheduler;
    }

    private static MarketCalendar krxRow(LocalDate date) {
        return MarketCalendar.builder()
                .calendarCode(CalendarCode.KRX)
                .calDate(date)
                .open(true)
                .source(CalendarSource.KIS_API)
                .build();
    }

    /** baseDate부터 24일치 연속 HolidayRow 목록을 생성한다(평일 Y, 주말 N — 단순화). */
    private static List<HolidayRow> responseFrom(LocalDate baseDate) {
        List<HolidayRow> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            LocalDate date = baseDate.plusDays(i);
            boolean weekend =
                    date.getDayOfWeek().getValue() == 6 || date.getDayOfWeek().getValue() == 7;
            rows.add(
                    new HolidayRow(
                            date.format(DATE_FORMAT), "05", "Y", "Y", weekend ? "N" : "Y", "Y"));
        }
        return rows;
    }

    @Nested
    @DisplayName("평시 — 정확히 1콜 (AC-4)")
    class NormalRefresh {

        @Test
        @DisplayName("마지막 연속 커버일이 어제이면 today-3 기준 1콜로 24일치를 upsert한다")
        void normalState_singleCallWith24DayUpsert() {
            // Arrange
            LocalDate yesterday = TODAY.minusDays(1);
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), eq(TODAY)))
                    .thenReturn(List.of(krxRow(yesterday)));
            LocalDate baseDate = TODAY.minusDays(3);
            when(kisHolidayClient.fetchCalendar(baseDate)).thenReturn(responseFrom(baseDate));

            // Act
            makeScheduler(new SimpleMeterRegistry()).refresh();

            // Assert
            verify(kisHolidayClient, times(1)).fetchCalendar(baseDate);
            verify(marketCalendarService, times(24))
                    .upsert(
                            eq(CalendarCode.KRX),
                            any(),
                            any(Boolean.class),
                            eq(CalendarSource.KIS_API));
        }
    }

    @Nested
    @DisplayName("갭 치유 — 순차 전체-호출 체이닝 (AC-6)")
    class GapHealing {

        @Test
        @DisplayName("갭 10일 — 각 호출 baseDate가 이전 응답 최대 날짜+1과 일치한다")
        void gapTenDaysAgo_chainsSequentialCalls() {
            // Arrange — 마지막 연속 커버일 = 10일 전
            LocalDate lastCovered = TODAY.minusDays(10);
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), eq(TODAY)))
                    .thenReturn(List.of(krxRow(lastCovered)));

            LocalDate firstBase = lastCovered.plusDays(1); // TODAY-9
            LocalDate secondBase = firstBase.plusDays(24); // 첫 응답 최대일+1
            when(kisHolidayClient.fetchCalendar(firstBase)).thenReturn(responseFrom(firstBase));
            when(kisHolidayClient.fetchCalendar(secondBase)).thenReturn(responseFrom(secondBase));

            // Act
            makeScheduler(new SimpleMeterRegistry()).refresh();

            // Assert — baseDate 체이닝 순서
            ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
            verify(kisHolidayClient, times(2)).fetchCalendar(captor.capture());
            assertThat(captor.getAllValues()).containsExactly(firstBase, secondBase);
        }
    }

    @Nested
    @DisplayName("갭 치유 상한(400일) 초과 — 자동 호출 중단 + 경보만 (AC-7)")
    class GapCapExceeded {

        @Test
        @DisplayName("미시딩(범위 내 행 없음) — 최대 MAX_GAP_CALLS 회 호출 후 중단, 경보 발생")
        void neverSeeded_boundedCallsWithAlert() {
            // Arrange — 시딩 범위(today-400~today) 내 행이 전혀 없음
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), eq(TODAY)))
                    .thenReturn(List.of());
            // 모든 baseDate 호출에 대해 24일치 정상 응답(끊김 없이 계속 진행되도록)
            when(kisHolidayClient.fetchCalendar(any(LocalDate.class)))
                    .thenAnswer(invocation -> responseFrom(invocation.getArgument(0)));

            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            // Act
            makeScheduler(registry).refresh();

            // Assert
            verify(kisHolidayClient, times(MarketCalendarRefreshScheduler.MAX_GAP_CALLS))
                    .fetchCalendar(any(LocalDate.class));
            double gapCapExceededCount =
                    registry.get(MarketCalendarRefreshScheduler.GAP_CAP_EXCEEDED_NAME)
                            .tag("calendar", "KRX")
                            .counter()
                            .count();
            assertThat(gapCapExceededCount).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("응답 검증 — 첫 bass_dt 불일치 시 미반영 + 경보 (AC-8)")
    class BassDtValidation {

        @Test
        @DisplayName("첫 bass_dt가 요청 BASS_DT와 다르면 upsert 미호출 + 경보 발생")
        void firstBassDtMismatch_skipsUpsertAndAlerts() {
            // Arrange — 평시 경로(1콜)에서 응답이 침묵 정규화되어 요청과 다른 날짜부터 시작
            LocalDate yesterday = TODAY.minusDays(1);
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), eq(TODAY)))
                    .thenReturn(List.of(krxRow(yesterday)));
            LocalDate requestedBase = TODAY.minusDays(3);
            LocalDate mismatchedFirst = requestedBase.plusDays(1); // 요청과 다른 첫 날짜
            when(kisHolidayClient.fetchCalendar(requestedBase))
                    .thenReturn(responseFrom(mismatchedFirst));

            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            // Act
            makeScheduler(registry).refresh();

            // Assert
            verify(marketCalendarService, never())
                    .upsert(
                            any(CalendarCode.class),
                            any(),
                            any(Boolean.class),
                            any(CalendarSource.class));
            double mismatchCount =
                    registry.get(MarketCalendarRefreshScheduler.BASS_DT_MISMATCH_NAME)
                            .counter()
                            .count();
            assertThat(mismatchCount).isEqualTo(1.0);
        }

        @Test
        @DisplayName("정상 응답(첫 bass_dt 일치)에서는 경보가 발생하지 않는다 (대조 케이스)")
        void matchingBassDt_noAlert() {
            LocalDate yesterday = TODAY.minusDays(1);
            when(marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            eq(CalendarCode.KRX), any(), eq(TODAY)))
                    .thenReturn(List.of(krxRow(yesterday)));
            LocalDate requestedBase = TODAY.minusDays(3);
            when(kisHolidayClient.fetchCalendar(requestedBase))
                    .thenReturn(responseFrom(requestedBase));

            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            makeScheduler(registry).refresh();

            double mismatchCount =
                    registry.get(MarketCalendarRefreshScheduler.BASS_DT_MISMATCH_NAME)
                            .counter()
                            .count();
            assertThat(mismatchCount).isZero();
        }
    }

    @Nested
    @DisplayName("cron 스케줄 — fixedDelay/fixedRate 미사용 (AC-9)")
    class CronSchedule {

        @Test
        @DisplayName("refresh() 메서드는 cron 속성만 사용하고 fixedDelay/fixedRate는 기본값(미사용)이다")
        void refreshMethod_usesOnlyCron() throws NoSuchMethodException {
            Method refreshMethod = MarketCalendarRefreshScheduler.class.getMethod("refresh");
            Scheduled scheduled = refreshMethod.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo(MarketCalendarRefreshScheduler.REFRESH_CRON);
            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
        }
    }
}
