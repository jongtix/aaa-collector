package com.aaa.collector.kis.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KisMarketScheduleTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    // 테스트에서는 항상 고정 Clock을 주입하여 시각 의존성을 제거한다.
    // KisMarketSchedule 자체는 Clock을 사용하지 않고 파라미터로 받은 ZonedDateTime으로 판정하므로
    // Clock은 생성자 주입용으로만 사용한다.
    private KisMarketSchedule schedule;

    @BeforeEach
    void setUp() {
        // 고정 Clock — 실제 판정은 isDomesticOpen/isOverseasOpen에 전달하는 ZonedDateTime이 결정한다.
        Clock fixedClock =
                Clock.fixed(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, KST).toInstant(), KST);
        schedule = new KisMarketSchedule(fixedClock);
    }

    // ── 국내 장 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isDomesticOpen")
    class IsDomesticOpen {

        @Test
        @DisplayName("평일 09:00 KST — 장중 true 반환")
        void weekdayAt09h00_returnsTrue() {
            // 2026-06-08 (월) 09:00 KST
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 8, 9, 0, 0, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isTrue();
        }

        @Test
        @DisplayName("경계값: 평일 08:55 KST — 개장 시각 포함(inclusive) true 반환")
        void weekdayAtExactOpen_08h55_returnsTrue() {
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 8, 8, 55, 0, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isTrue();
        }

        @Test
        @DisplayName("경계값: 평일 08:54 KST — 개장 전 false 반환")
        void weekdayBefore08h55_returnsFalse() {
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 8, 8, 54, 59, 999_999_999, KST);

            assertThat(schedule.isDomesticOpen(now)).isFalse();
        }

        @Test
        @DisplayName("경계값: 평일 15:35 KST — 폐장 시각 미포함(exclusive) false 반환")
        void weekdayAtExactClose_15h35_returnsFalse() {
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 8, 15, 35, 0, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isFalse();
        }

        @Test
        @DisplayName("경계값: 평일 15:34 KST — 폐장 직전 true 반환")
        void weekdayBefore15h35_returnsTrue() {
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 8, 15, 34, 59, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isTrue();
        }

        @Test
        @DisplayName("토요일 09:00 KST — 주말 false 반환")
        void saturdayAt09h00_returnsFalse() {
            // 2026-06-13 (토) 09:00 KST
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 13, 9, 0, 0, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isFalse();
        }

        @Test
        @DisplayName("일요일 09:00 KST — 주말 false 반환")
        void sundayAt09h00_returnsFalse() {
            // 2026-06-14 (일) 09:00 KST
            ZonedDateTime now = ZonedDateTime.of(2026, 6, 14, 9, 0, 0, 0, KST);

            assertThat(schedule.isDomesticOpen(now)).isFalse();
        }
    }

    // ── 해외 장 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isOverseasOpen")
    class IsOverseasOpen {

        @Test
        @DisplayName("EDT 하절기: 평일 NY 09:25 — 장중 true 반환")
        void edtWeekdayAtExactOpen_09h25_returnsTrue() {
            // 2026-06-08 (월) 09:25 EDT (UTC-4) → KST 22:25
            Instant instant = ZonedDateTime.of(2026, 6, 8, 9, 25, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isTrue();
        }

        @Test
        @DisplayName("EST 동절기: 평일 NY 09:25 — 장중 true 반환")
        void estWeekdayAtExactOpen_09h25_returnsTrue() {
            // 2026-01-05 (월) 09:25 EST (UTC-5) → KST 23:25 (전날 밤)
            Instant instant = ZonedDateTime.of(2026, 1, 5, 9, 25, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isTrue();
        }

        @Test
        @DisplayName("평일 NY 09:24 — 개장 전 false 반환")
        void weekdayBefore09h25_returnsFalse() {
            Instant instant =
                    ZonedDateTime.of(2026, 6, 8, 9, 24, 59, 999_999_999, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isFalse();
        }

        @Test
        @DisplayName("경계값: 평일 NY 16:05 — 폐장 시각 미포함(exclusive) false 반환")
        void weekdayAtExactClose_16h05_returnsFalse() {
            Instant instant = ZonedDateTime.of(2026, 6, 8, 16, 5, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isFalse();
        }

        @Test
        @DisplayName("평일 NY 16:04 — 폐장 직전 true 반환")
        void weekdayBefore16h05_returnsTrue() {
            Instant instant = ZonedDateTime.of(2026, 6, 8, 16, 4, 59, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isTrue();
        }

        @Test
        @DisplayName("주말(토요일 NY 시간) — false 반환")
        void saturdayNyTime_returnsFalse() {
            // 2026-06-13 (토) 10:00 NY
            Instant instant = ZonedDateTime.of(2026, 6, 13, 10, 0, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isFalse();
        }

        @Test
        @DisplayName("주말(일요일 NY 시간) — false 반환")
        void sundayNyTime_returnsFalse() {
            // 2026-06-14 (일) 10:00 NY
            Instant instant = ZonedDateTime.of(2026, 6, 14, 10, 0, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isFalse();
        }

        @Test
        @DisplayName("NY 16:06 이후 — 폐장 후 false 반환")
        void afterClose_returnsFalse() {
            Instant instant = ZonedDateTime.of(2026, 6, 8, 16, 6, 0, 0, NEW_YORK).toInstant();
            ZonedDateTime now = instant.atZone(KST);

            assertThat(schedule.isOverseasOpen(now)).isFalse();
        }
    }
}
