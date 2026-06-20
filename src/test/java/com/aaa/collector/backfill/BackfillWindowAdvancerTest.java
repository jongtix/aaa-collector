package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 백필 윈도우 anchor 전진기 명세 (SPEC-COLLECTOR-BACKFILL-001 T5, AC-3).
 *
 * <p>순수 로직 — KIS/Spring 비의존. SPAN(기본 150 달력일)·anchor-skip-max(기본 10)는 생성자 주입.
 */
class BackfillWindowAdvancerTest {

    private static final int SPAN_CALENDAR_DAYS = 150;
    private static final int ANCHOR_SKIP_MAX = 10;
    private final BackfillWindowAdvancer advancer =
            new BackfillWindowAdvancer(SPAN_CALENDAR_DAYS, ANCHOR_SKIP_MAX);

    @Nested
    @DisplayName("anchor 전진 — −1 달력일 (AC-3.2, MA-02)")
    class NextAnchor {

        @Test
        @DisplayName("AC-3.2: oldest=금 2024-06-07이면 다음 anchor=2024-06-06 (−1 달력일)")
        void friday_minusOneCalendarDay() {
            LocalDate next = advancer.nextAnchor(LocalDate.of(2024, 6, 7));

            assertThat(next).isEqualTo(LocalDate.of(2024, 6, 6));
        }

        @Test
        @DisplayName("−1 달력일은 영업일 계산이 아님 — 월요일이면 일요일로 떨어져도 그대로")
        void monday_fallsOnSunday() {
            // 2024-06-10(월) → 2024-06-09(일). KIS가 이전 거래일을 반환하므로 무해.
            LocalDate next = advancer.nextAnchor(LocalDate.of(2024, 6, 10));

            assertThat(next).isEqualTo(LocalDate.of(2024, 6, 9));
        }
    }

    @Nested
    @DisplayName("그룹 A SPAN — ≥150 달력일 보장 (AC-1.2b, REQ-013a)")
    class GroupASpan {

        @Test
        @DisplayName("anchor로부터 from-date는 최소 150 달력일 이전")
        void fromDate_atLeast150CalendarDaysBack() {
            LocalDate anchor = LocalDate.of(2024, 6, 30);

            LocalDate from = advancer.groupASpanFromDate(anchor);

            assertThat(ChronoUnit.DAYS.between(from, anchor)).isGreaterThanOrEqualTo(150);
        }

        @Test
        @DisplayName("AC-1.2b: 좁은 SPAN(100 달력일)은 사용 불가 — from-date가 그보다 더 과거여야 함")
        void fromDate_widerThan100CalendarDays() {
            LocalDate anchor = LocalDate.of(2024, 6, 30);

            LocalDate from = advancer.groupASpanFromDate(anchor);

            assertThat(from).isBefore(anchor.minusDays(100));
        }
    }

    @Nested
    @DisplayName("그룹 B anchor 거부 보정 — rt_cd=2 (AC-3.3, REQ-016)")
    class RejectedAnchorCorrection {

        @Test
        @DisplayName("AC-3.3: 토요일 anchor rt_cd=2면 −1 달력일 보정하고 무전진 카운트 안 함")
        void saturdayRejected_correctsMinusOneDay() {
            // 2024-06-08(토) anchor가 rt_cd=2로 거부됨
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 8), 0);

            assertThat(result.correctedAnchor()).isEqualTo(LocalDate.of(2024, 6, 7));
            assertThat(result.exhausted()).isFalse();
            assertThat(result.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("보정은 매번 1씩 누적 — 직전 attempts 기반으로 증가")
        void correction_accumulatesAttempts() {
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 5), 4);

            assertThat(result.attempts()).isEqualTo(5);
            assertThat(result.exhausted()).isFalse();
        }

        @Test
        @DisplayName("AC-3.3: anchor-skip-max(10) 초과 시 exhausted=true → 당 회차 skip")
        void exhaustsAtSkipMax() {
            // attemptsSoFar=9 → 10번째 보정 → 한도 도달 → exhausted
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 1), 9);

            assertThat(result.attempts()).isEqualTo(10);
            assertThat(result.exhausted()).isTrue();
        }
    }
}
