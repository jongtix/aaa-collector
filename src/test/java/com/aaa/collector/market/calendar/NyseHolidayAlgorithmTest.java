package com.aaa.collector.market.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link NyseHolidayAlgorithm} 단위 테스트 (SPEC-COLLECTOR-CALENDAR-001 TASK-004).
 *
 * <p>{@code UsMarketSessionGate}에서 추출된 순수 계산 로직 — 동일 계산 결과를 재확인한다(♻️ 리팩터, 동작 무변경).
 */
@DisplayName("NyseHolidayAlgorithm — NYSE 표준 휴장일 결정론 알고리즘 (REQ-001~005)")
class NyseHolidayAlgorithmTest {

    @Nested
    @DisplayName("computeObservedHolidays — 연도별 10개 표준 휴장일 계산")
    class ComputeObservedHolidays {

        @Test
        @DisplayName("2026년 NYSE 표준 휴장일 10개 정확 계산")
        void computeObservedHolidays_2026_containsExpectedDates() {
            Set<LocalDate> holidays2026 = NyseHolidayAlgorithm.computeObservedHolidays(2026);

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
                                    2026, 7, 3), // Independence Day observed (Jul 4 Sat -> Fri)
                            LocalDate.of(2026, 9, 7), // Labor Day (1st Mon Sep)
                            LocalDate.of(2026, 11, 26), // Thanksgiving (4th Thu Nov)
                            LocalDate.of(2026, 12, 25) // Christmas (Fri)
                            );
        }

        @Test
        @DisplayName("Good Friday 2025 — Easter 2025(Apr 20) - 2 = Apr 18")
        void goodFriday_2025_isCorrect() {
            assertThat(NyseHolidayAlgorithm.computeObservedHolidays(2025))
                    .contains(LocalDate.of(2025, 4, 18));
        }

        @Test
        @DisplayName("토요일 휴장일 → 직전 금요일 관측 (REQ-004)")
        void saturdayHoliday_observedOnFriday() {
            // Jul 4, 2026 is Saturday -> observed Fri Jul 3, 2026
            Set<LocalDate> holidays2026 = NyseHolidayAlgorithm.computeObservedHolidays(2026);
            assertThat(holidays2026).contains(LocalDate.of(2026, 7, 3));
            assertThat(holidays2026).doesNotContain(LocalDate.of(2026, 7, 4));
        }

        @Test
        @DisplayName("일요일 휴장일 → 다음 월요일 관측 (REQ-005)")
        void sundayHoliday_observedOnMonday() {
            // Christmas 2022: Dec 25, 2022 = Sunday -> observed Mon Dec 26, 2022
            Set<LocalDate> holidays2022 = NyseHolidayAlgorithm.computeObservedHolidays(2022);
            assertThat(holidays2022).contains(LocalDate.of(2022, 12, 26));
            assertThat(holidays2022).doesNotContain(LocalDate.of(2022, 12, 25));
        }
    }

    @Nested
    @DisplayName("isOpenDay — 개장일 판정 (TASK-006 전용, 상태·외부 호출 없음)")
    class IsOpenDay {

        @Test
        @DisplayName("평일 비휴장일 → true")
        void weekdayNotHoliday_returnsTrue() {
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2026, 7, 6))).isTrue();
        }

        @Test
        @DisplayName("토요일 → false (주말)")
        void saturday_returnsFalse() {
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2026, 7, 11))).isFalse();
        }

        @Test
        @DisplayName("일요일 → false (주말)")
        void sunday_returnsFalse() {
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2026, 7, 12))).isFalse();
        }

        @Test
        @DisplayName("관측 휴장일(2026-07-03, Independence Day 대체) → false")
        void observedHoliday_returnsFalse() {
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2026, 7, 3))).isFalse();
        }

        @Test
        @DisplayName("연도 경계를 넘는 판정 — 2026-12-31(목, 개장)·2027-01-01(금, New Year's Day 휴장)")
        void yearBoundary_computesEachYearIndependently() {
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2026, 12, 31))).isTrue();
            assertThat(NyseHolidayAlgorithm.isOpenDay(LocalDate.of(2027, 1, 1))).isFalse();
        }
    }
}
