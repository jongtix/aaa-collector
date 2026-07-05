package com.aaa.collector.market.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.websocket.KisMarketSchedule;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("UsMarketSessionGate — 미국 시장 세션 게이트 (SPEC-COLLECTOR-USMKT-001)")
class UsMarketSessionGateTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    /** 2026-07-06 10:00 ET (Monday) — 평일 장중. UTC: 2026-07-06 14:00Z (EDT = UTC-4) */
    private static final Instant MON_TRADING_INSTANT = Instant.parse("2026-07-06T14:00:00Z");

    /** 2026-07-04 10:00 ET (Saturday) — 주말. UTC: 2026-07-04 14:00Z */
    private static final Instant SAT_INSTANT = Instant.parse("2026-07-04T14:00:00Z");

    /** 2026-07-06 08:00 ET (Monday) — 평일 장외(09:25 전). UTC: 2026-07-06 12:00Z */
    private static final Instant BEFORE_OPEN_INSTANT = Instant.parse("2026-07-06T12:00:00Z");

    private UsMarketSessionGate createGate(List<LocalDate> extraHolidays, Instant now) {
        Clock clock = Clock.fixed(now, NEW_YORK);
        KisMarketSchedule schedule = new KisMarketSchedule(clock);
        UsMarketProperties props = new UsMarketProperties();
        props.setExtraHolidays(extraHolidays);
        UsMarketSessionGate gate =
                new UsMarketSessionGate(new SimpleMeterRegistry(), schedule, clock, props);
        gate.init();
        return gate;
    }

    private UsMarketSessionGate createGate(List<LocalDate> extraHolidays) {
        return createGate(extraHolidays, MON_TRADING_INSTANT);
    }

    private UsMarketSessionGate createGate() {
        return createGate(List.of(), MON_TRADING_INSTANT);
    }

    // -------------------------------------------------------------------------
    // AC-1: NYSE 휴장일(2026-07-04) isOpenDay=false — 토요일이므로 주말 판정
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isOpenDay — 개장일 판정 (REQ-006/007, AC-1/AC-9/AC-11)")
    class IsOpenDay {

        @Test
        @DisplayName("Independence Day(2026-07-04 토) → false (주말)")
        void isOpenDay_IndependenceDay_returnsFalse() {
            UsMarketSessionGate gate = createGate();
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 4))).isFalse();
        }

        @Test
        @DisplayName("Independence Day 대체 관측일(2026-07-03 금) → false (NYSE 관측 휴장)")
        void isOpenDay_observedIndependenceDay_returnsFalse() {
            UsMarketSessionGate gate = createGate();
            // Jul 4 2026 = Sat → observed Fri Jul 3
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 3))).isFalse();
        }

        @Test
        @DisplayName("평일 비휴장일(2026-07-06 월) → true")
        void isOpenDay_weekday_notHoliday_returnsTrue() {
            UsMarketSessionGate gate = createGate();
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 6))).isTrue();
        }

        @Test
        @DisplayName("토요일(비휴장 포함) → false (주말)")
        void isOpenDay_saturday_returnsFalse() {
            UsMarketSessionGate gate = createGate();
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 11))).isFalse();
        }

        @Test
        @DisplayName("일요일 → false (주말)")
        void isOpenDay_sunday_returnsFalse() {
            UsMarketSessionGate gate = createGate();
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 12))).isFalse();
        }

        // AC-7: fail-open — init() 미호출 시 true
        @Test
        @DisplayName("observedHolidays 미초기화(fail-open) → true (AC-7)")
        void isOpenDay_emptySet_failOpen_returnsTrue() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            UsMarketProperties props = new UsMarketProperties();
            // init() 미호출 → observedHolidays = Set.of()
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(new SimpleMeterRegistry(), schedule, clock, props);

            // 2026-07-03(금) = observed Independence Day 이지만, fail-open이므로 true
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 3))).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // AC-2: ET 평일 장중 isMarketOpenNow=true (경계 09:25/16:05)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("isMarketOpenNow — ET 장중 여부 (REQ-008, AC-2/AC-5/AC-6)")
    class IsMarketOpenNow {

        @Test
        @DisplayName("ET 평일 10:00 → true (AC-2)")
        void isMarketOpenNow_withinTradingHours_returnsTrue() {
            UsMarketSessionGate gate = createGate();
            assertThat(gate.isMarketOpenNow()).isTrue();
        }

        @Test
        @DisplayName("ET 평일 09:00 (장 개시 전) → false")
        void isMarketOpenNow_beforeOpen_returnsFalse() {
            UsMarketSessionGate gate = createGate(List.of(), BEFORE_OPEN_INSTANT);
            assertThat(gate.isMarketOpenNow()).isFalse();
        }

        @Test
        @DisplayName("ET 주말 10:00 → false")
        void isMarketOpenNow_weekend_returnsFalse() {
            UsMarketSessionGate gate = createGate(List.of(), SAT_INSTANT);
            assertThat(gate.isMarketOpenNow()).isFalse();
        }

        @Test
        @DisplayName("ET 평일 장중 + 관측 휴장일(Jul 3) → false (holiday gate)")
        void isMarketOpenNow_holiday_returnsFalse() {
            // 2026-07-03 10:00 ET — Jul 3 is observed Independence Day
            Instant jul3Trading = Instant.parse("2026-07-03T14:00:00Z"); // 10:00 ET (EDT=UTC-4)
            UsMarketSessionGate gate = createGate(List.of(), jul3Trading);
            // Jul 3 is the observed holiday for Independence Day 2026
            assertThat(gate.isMarketOpenNow()).isFalse();
        }

        @Test
        @DisplayName("ET 09:25 경계(포함) → true")
        void isMarketOpenNow_atOpenBoundary_returnsTrue() {
            // 2026-07-06 09:25 ET = 13:25 UTC
            Instant atOpen = Instant.parse("2026-07-06T13:25:00Z");
            UsMarketSessionGate gate = createGate(List.of(), atOpen);
            assertThat(gate.isMarketOpenNow()).isTrue();
        }

        @Test
        @DisplayName("ET 16:05 경계(미포함) → false")
        void isMarketOpenNow_atCloseBoundary_returnsFalse() {
            // 2026-07-06 16:05 ET = 20:05 UTC
            Instant atClose = Instant.parse("2026-07-06T20:05:00Z");
            UsMarketSessionGate gate = createGate(List.of(), atClose);
            assertThat(gate.isMarketOpenNow()).isFalse();
        }
    }

    @Nested
    @DisplayName("반일 조기폐장 (SPEC-OBSV-WATERMARK-001 REQ-WM-030)")
    class EarlyClose {

        // 2026-11-27(토요일 아님, 금요일=Black Friday) — NYSE 표준 10휴장일 미포함, 반일 config 대상
        private static final LocalDate BLACK_FRIDAY_2026 = LocalDate.of(2026, 11, 27);

        // 2026-11-27 10:00 ET(EST, UTC-5) = 15:00 UTC — 반일 폐장(13:00 ET) 이전
        private static final Instant BEFORE_EARLY_CLOSE = Instant.parse("2026-11-27T15:00:00Z");

        // 2026-11-27 14:00 ET(EST) = 19:00 UTC — 반일 폐장(13:00 ET) 이후
        private static final Instant AFTER_EARLY_CLOSE = Instant.parse("2026-11-27T19:00:00Z");

        // 2026-11-27 13:00 ET(EST) 정각 = 18:00 UTC — 경계값(미포함)
        private static final Instant AT_EARLY_CLOSE_BOUNDARY =
                Instant.parse("2026-11-27T18:00:00Z");

        private UsMarketSessionGate createGateWithEarlyClose(Instant now) {
            Clock clock = Clock.fixed(now, NEW_YORK);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            UsMarketProperties props = new UsMarketProperties();
            props.setEarlyCloseDays(List.of(BLACK_FRIDAY_2026));
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(new SimpleMeterRegistry(), schedule, clock, props);
            gate.init();
            return gate;
        }

        @Test
        @DisplayName("반일 config 대상 날짜 + 13:00 ET 이전 → true (정상 개장)")
        void beforeEarlyCloseTime_returnsTrue() {
            UsMarketSessionGate gate = createGateWithEarlyClose(BEFORE_EARLY_CLOSE);
            assertThat(gate.isMarketOpenNow()).isTrue();
        }

        @Test
        @DisplayName("반일 config 대상 날짜 + 13:00 ET 이후 → false (반일 폐장, REQ-WM-030)")
        void afterEarlyCloseTime_returnsFalse() {
            UsMarketSessionGate gate = createGateWithEarlyClose(AFTER_EARLY_CLOSE);
            assertThat(gate.isMarketOpenNow()).isFalse();
        }

        @Test
        @DisplayName("13:00 ET 경계(미포함) → false")
        void atEarlyCloseBoundary_returnsFalse() {
            UsMarketSessionGate gate = createGateWithEarlyClose(AT_EARLY_CLOSE_BOUNDARY);
            assertThat(gate.isMarketOpenNow()).isFalse();
        }

        @Test
        @DisplayName("반일 config 미설정이면 동일 시각에도 정상 개장(16:05까지) — 기존 extraHolidays 경로 불변")
        void withoutEarlyCloseConfig_regularCloseApplies() {
            // Arrange — earlyCloseDays 미설정(빈 목록) 게이트
            Clock clock = Clock.fixed(AFTER_EARLY_CLOSE, NEW_YORK);
            KisMarketSchedule schedule = new KisMarketSchedule(clock);
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            new SimpleMeterRegistry(), schedule, clock, new UsMarketProperties());
            gate.init();

            assertThat(gate.isMarketOpenNow()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // AC-9: NYSE 10개 표준 휴장일 알고리즘 정확 계산
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("computeObservedHolidays — NYSE 표준 휴장일 알고리즘 (REQ-001~005, AC-9)")
    class ComputeObservedHolidays {

        @Test
        @DisplayName("2026년 NYSE 표준 휴장일 10개 정확 계산 (AC-9)")
        void computeObservedHolidays_2026_containsExpectedDates() {
            // Arrange
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            new SimpleMeterRegistry(),
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());

            gate.init();

            // Act
            Set<LocalDate> holidays2026 = gate.computeObservedHolidays(2026);

            // Assert — 2026년 10개 표준 휴장일
            assertThat(holidays2026)
                    .as("2026 NYSE 표준 휴장일 10개 포함")
                    .containsExactlyInAnyOrder(
                            LocalDate.of(2026, 1, 1), // New Year's Day (Thu)
                            LocalDate.of(2026, 1, 19), // MLK Day (3rd Mon Jan)
                            LocalDate.of(2026, 2, 16), // Presidents' Day (3rd Mon Feb)
                            LocalDate.of(2026, 4, 3), // Good Friday (Easter Apr 5 - 2)
                            LocalDate.of(2026, 5, 25), // Memorial Day (last Mon May)
                            LocalDate.of(2026, 6, 19), // Juneteenth (Fri)
                            LocalDate.of(
                                    2026, 7,
                                    3), // Independence Day observed (Jul 4 Sat → Fri Jul 3)
                            LocalDate.of(2026, 9, 7), // Labor Day (1st Mon Sep)
                            LocalDate.of(2026, 11, 26), // Thanksgiving (4th Thu Nov)
                            LocalDate.of(2026, 12, 25) // Christmas (Fri)
                            );
        }

        @Test
        @DisplayName("Good Friday 2025 — Easter 2025(Apr 20) - 2 = Apr 18")
        void goodFriday_2025_isCorrect() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            new SimpleMeterRegistry(),
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());

            assertThat(gate.computeObservedHolidays(2025))
                    .contains(LocalDate.of(2025, 4, 18)); // Good Friday 2025
        }

        // AC-11: 토요일 → 직전 금요일 (REQ-004)
        @Test
        @DisplayName("토요일 휴장일 → 직전 금요일 관측 (AC-11, REQ-004)")
        void saturday_holiday_observedOnFriday() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            new SimpleMeterRegistry(),
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());

            // Jul 4, 2026 is Saturday → observed Fri Jul 3, 2026
            Set<LocalDate> holidays2026 = gate.computeObservedHolidays(2026);
            assertThat(holidays2026).contains(LocalDate.of(2026, 7, 3));
            assertThat(holidays2026).doesNotContain(LocalDate.of(2026, 7, 4));
        }

        // AC-11: 일요일 → 다음 월요일 (REQ-005)
        @Test
        @DisplayName("일요일 휴장일 → 다음 월요일 관측 (AC-11, REQ-005)")
        void sunday_holiday_observedOnMonday() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            new SimpleMeterRegistry(),
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());

            // Christmas 2022: Dec 25, 2022 = Sunday → observed Mon Dec 26, 2022
            Set<LocalDate> holidays2022 = gate.computeObservedHolidays(2022);
            assertThat(holidays2022).contains(LocalDate.of(2022, 12, 26));
            assertThat(holidays2022).doesNotContain(LocalDate.of(2022, 12, 25));
        }
    }

    // -------------------------------------------------------------------------
    // AC-10: extra-holidays 오버라이드 (REQ-002/003)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("extra-holidays 오버라이드 (REQ-002/003, AC-10)")
    class ExtraHolidays {

        @Test
        @DisplayName("extra-holidays 빈 목록 → 정상 동작 (AC-10)")
        void extraHolidays_empty_works() {
            UsMarketSessionGate gate = createGate(List.of());
            // 평일 비휴장 → true
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 6))).isTrue();
        }

        @Test
        @DisplayName("extra-holidays 추가 날짜 → isOpenDay=false (AC-10)")
        void extraHolidays_override_addsToSet() {
            LocalDate customHoliday = LocalDate.of(2026, 7, 6); // 평일(Mon)
            UsMarketSessionGate gate = createGate(List.of(customHoliday));
            assertThat(gate.isOpenDay(customHoliday)).isFalse();
        }

        @Test
        @DisplayName("extra-holidays 날짜가 없으면 해당 평일 → true")
        void extraHolidays_notInOverride_remainsOpen() {
            LocalDate customHoliday = LocalDate.of(2026, 7, 6);
            UsMarketSessionGate gate = createGate(List.of(customHoliday));
            // 2026-07-07(Tue)은 extra-holidays에 없으므로 open
            assertThat(gate.isOpenDay(LocalDate.of(2026, 7, 7))).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // AC-3: Prometheus 게이지 등록 (REQ-010/011)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Prometheus 게이지 등록 (REQ-010/011, AC-3)")
    class PrometheusGauges {

        @Test
        @DisplayName("us_market_open + us_market_gate_holiday_count 두 게이지가 registry에 등록된다")
        void bothGaugesRegisteredAtConstruction() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());
            gate.init();

            assertThat(registry.get(UsMarketSessionGate.US_MARKET_OPEN_NAME).gauge()).isNotNull();
            assertThat(registry.get(UsMarketSessionGate.US_MARKET_HOLIDAY_COUNT_NAME).gauge())
                    .isNotNull();
        }

        @Test
        @DisplayName("us_market_open = 1.0 (ET 장중 평일)")
        void usMarketOpen_duringTradingHours_isOne() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());
            gate.init();

            Gauge g = registry.get(UsMarketSessionGate.US_MARKET_OPEN_NAME).gauge();
            assertThat(g.value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("us_market_gate_holiday_count > 0 (init 후 휴장일 적재)")
        void holidayCount_afterInit_isPositive() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());
            gate.init();

            Gauge g = registry.get(UsMarketSessionGate.US_MARKET_HOLIDAY_COUNT_NAME).gauge();
            // 2년치(2026+2027) = 최소 20 휴장일
            assertThat(g.value()).isGreaterThanOrEqualTo(20.0);
        }
    }

    @Nested
    @DisplayName("expected watermark 게이지 (SPEC-OBSV-WATERMARK-001 REQ-WM-006/009)")
    class ExpectedWatermark {

        private static final ZoneId KST = ZoneId.of("Asia/Seoul");

        /** 2026-07-06 17:00 ET (Monday, 세션 마감 이후). UTC: 2026-07-06 21:00Z (EDT = UTC-4) */
        private static final Instant MON_AFTER_CLOSE_INSTANT =
                Instant.parse("2026-07-06T21:00:00Z");

        @Test
        @DisplayName("init() 호출 전(holiday_count=0)에는 게이지 자체가 absent이다 (REQ-WM-009)")
        void beforeInit_gaugeIsAbsent() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new UsMarketSessionGate(
                    registry, new KisMarketSchedule(clock), clock, new UsMarketProperties());

            Gauge gauge = registry.find(UsMarketSessionGate.EXPECTED_WATERMARK_NAME).gauge();
            assertThat(gauge).isNull();
        }

        @Test
        @DisplayName("holiday_count>=20 도달 시 expected watermark 게이지가 등록된다")
        void registersGaugeWhenHolidayCountReady() {
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());

            gate.init();

            Gauge gauge = registry.get(UsMarketSessionGate.EXPECTED_WATERMARK_NAME).gauge();
            assertThat(gauge).isNotNull();
        }

        @Test
        @DisplayName("세션 마감(16:00 ET) 이전이면 전날부터 역방향으로 개장일을 탐색한다")
        void beforeClose_searchesBackwardFromYesterday() {
            // Arrange — 2026-07-06(월) 10:00 ET, 마감 전. 전날(07-05 일)부터 역탐색:
            // 07-05(일,휴장)→07-04(토,휴장)→07-03(금, Independence Day observed)→07-02(목, 개장)
            Clock clock = Clock.fixed(MON_TRADING_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());
            gate.init();

            Gauge gauge = registry.get(UsMarketSessionGate.EXPECTED_WATERMARK_NAME).gauge();
            long expected = LocalDate.of(2026, 7, 2).atStartOfDay(KST).toEpochSecond();
            assertThat(gauge.value()).isEqualTo((double) expected);
        }

        @Test
        @DisplayName("세션 마감(16:00 ET) 이후이고 오늘이 개장일이면 오늘 날짜를 반환한다")
        void afterClose_returnsToday() {
            Clock clock = Clock.fixed(MON_AFTER_CLOSE_INSTANT, NEW_YORK);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            UsMarketSessionGate gate =
                    new UsMarketSessionGate(
                            registry,
                            new KisMarketSchedule(clock),
                            clock,
                            new UsMarketProperties());
            gate.init();

            Gauge gauge = registry.get(UsMarketSessionGate.EXPECTED_WATERMARK_NAME).gauge();
            long expected = LocalDate.of(2026, 7, 6).atStartOfDay(KST).toEpochSecond();
            assertThat(gauge.value()).isEqualTo((double) expected);
        }
    }
}
