package com.aaa.collector.market.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.Set;

/**
 * NYSE 표준 10개 휴장일 결정론 알고리즘 (SPEC-COLLECTOR-CALENDAR-001 TASK-004).
 *
 * <p>{@code UsMarketSessionGate.computeObservedHolidays(int)} 등 순수 계산 로직을 추출한 것이다(♻️ 리팩터, 동작 무변경).
 * 외부 API 호출·상태 없음 — 임의 연도에 대해 결정론적으로 계산 가능하다. 일일 캘린더 갱신 배치({@code MarketCalendarRefreshScheduler},
 * TASK-006)와 기존 게이트({@code UsMarketSessionGate}, 하위 호환 위임) 양쪽이 재사용한다.
 */
public final class NyseHolidayAlgorithm {

    private NyseHolidayAlgorithm() {}

    /**
     * 지정 연도의 NYSE 표준 10개 휴장일의 관측일(observed date)을 계산한다 (REQ-001~005).
     *
     * @param year 계산 연도
     * @return 해당 연도의 NYSE 관측 휴장일 Set (주말 대체일 적용 완료)
     */
    public static Set<LocalDate> computeObservedHolidays(int year) {
        Set<LocalDate> holidays = new HashSet<>();

        // 1. New Year's Day — Jan 1 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JANUARY, 1)));

        // 2. MLK Day — 1월 3번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3));

        // 3. Presidents' Day — 2월 3번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));

        // 4. Good Friday — 부활절 2일 전 (Meeus/Jones/Butcher, REQ-001/003)
        holidays.add(easterSunday(year).minusDays(2));

        // 5. Memorial Day — 5월 마지막 월요일 (REQ-001)
        holidays.add(lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY));

        // 6. Juneteenth — Jun 19 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JUNE, 19)));

        // 7. Independence Day — Jul 4 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JULY, 4)));

        // 8. Labor Day — 9월 첫 번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));

        // 9. Thanksgiving — 11월 4번째 목요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));

        // 10. Christmas Day — Dec 25 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));

        return holidays;
    }

    /**
     * 지정 날짜가 NYSE 개장일인지 결정론적으로 판정한다 (SPEC-COLLECTOR-CALENDAR-001 TASK-006 — 일일 갱신 배치 전용, 외부 호출·상태
     * 없음).
     *
     * <p>{@code UsMarketProperties.extraHolidays} 등 설정 기반 오버라이드는 반영하지 않는다 — 그 값은 게이트({@code
     * UsMarketSessionGate}) 전용 관심사이며 이 순수 알고리즘의 책임 밖이다.
     *
     * @param date 판정 대상 날짜
     * @return 개장일이면 {@code true}
     */
    public static boolean isOpenDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !computeObservedHolidays(date.getYear()).contains(date);
    }

    /**
     * 토요일 → 직전 금요일, 일요일 → 다음 월요일, 평일 → 그대로 (REQ-004/005).
     *
     * @param date 원래 휴장일
     * @return 관측(observed) 휴장일
     */
    private static LocalDate observed(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1); // Friday
            case SUNDAY -> date.plusDays(1); // Monday
            default -> date;
        };
    }

    /**
     * 지정 연월의 n번째 특정 요일을 반환한다.
     *
     * @param year 연도
     * @param month 월
     * @param dow 요일
     * @param n 몇 번째 (1-based)
     * @return n번째 해당 요일 날짜
     */
    private static LocalDate nthWeekdayOfMonth(int year, Month month, DayOfWeek dow, int n) {
        LocalDate first = LocalDate.of(year, month, 1);
        int daysUntilDow = (dow.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        return first.plusDays(daysUntilDow + (n - 1) * 7);
    }

    /**
     * 지정 연월의 마지막 특정 요일을 반환한다.
     *
     * @param year 연도
     * @param month 월
     * @param dow 요일
     * @return 해당 월의 마지막 해당 요일 날짜
     */
    private static LocalDate lastWeekdayOfMonth(int year, Month month, DayOfWeek dow) {
        LocalDate last = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        int daysBack = (last.getDayOfWeek().getValue() - dow.getValue() + 7) % 7;
        return last.minusDays(daysBack);
    }

    /**
     * Meeus/Jones/Butcher 알고리즘으로 부활절 일요일을 계산한다 (REQ-003).
     *
     * @param year 연도
     * @return 해당 연도의 부활절 일요일
     */
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
