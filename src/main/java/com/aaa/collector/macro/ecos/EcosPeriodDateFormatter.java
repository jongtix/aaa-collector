package com.aaa.collector.macro.ecos;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * ECOS 요청 URL의 주기별 날짜 포맷·당일 수집 윈도우·백필 시작 리터럴을 계산하는 헬퍼 (SPEC-COLLECTOR-ECOS-DATEFMT-001
 * REQ-ECOSFMT-001~004, 006).
 *
 * <p>주기 코드가 D/M/Q 이외의 값이면 무음 폴백 없이 {@link IllegalArgumentException}을 던진다 (REQ-ECOSFMT-006).
 */
final class EcosPeriodDateFormatter {

    private static final String UNSUPPORTED_PERIOD_MESSAGE = "지원하지 않는 주기 코드: ";

    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** M 주기 요청 URL 날짜 형식(YYYYMM, 6자리, REQ-ECOSFMT-001). */
    private static final DateTimeFormatter MONTHLY_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 당일 수집 윈도우 — 일별 최근 30일 */
    private static final int DAILY_WINDOW = 30;

    /** 당일 수집 윈도우 — 월별 최근 3개월 */
    private static final int MONTHLY_WINDOW = 3;

    /** 당일 수집 윈도우 — 분기별 최근 2분기 */
    private static final int QUARTERLY_WINDOW = 6;

    private EcosPeriodDateFormatter() {}

    /** 당일 수집 윈도우 시작일을 주기별로 계산한다(REQ-ECOSFMT-004 — 윈도우 크기 불변, 날짜 포맷만 정정). */
    static LocalDate windowStart(LocalDate today, String period) {
        return switch (period) {
            case "D" -> today.minusDays(DAILY_WINDOW);
            case "M" -> today.minusMonths(MONTHLY_WINDOW).withDayOfMonth(1);
            case "Q" -> today.minusMonths(QUARTERLY_WINDOW).withDayOfMonth(1);
            default -> throw new IllegalArgumentException(UNSUPPORTED_PERIOD_MESSAGE + period);
        };
    }

    /**
     * 백필 경로의 시작일을 주기별 최소 유효 리터럴로 반환한다(REQ-ECOSFMT-002) — D=19000101, M=190001, Q=1900Q1. 전 주기 공통
     * 리터럴을 사용하지 않는다.
     */
    static String backfillStartLiteral(String period) {
        return switch (period) {
            case "D" -> "19000101";
            case "M" -> "190001";
            case "Q" -> "1900Q1";
            default -> throw new IllegalArgumentException(UNSUPPORTED_PERIOD_MESSAGE + period);
        };
    }

    /**
     * 날짜를 주기별 요청 형식으로 포맷한다(REQ-ECOSFMT-001) — D=YYYYMMDD(8자리), M=YYYYMM(6자리),
     * Q=YYYYQN(REQ-ECOSFMT-003 — 달력 월을 분기 번호로 변환).
     */
    static String formatDateForPeriod(LocalDate date, String period) {
        return switch (period) {
            case "D" -> date.format(DAILY_FMT);
            case "M" -> date.format(MONTHLY_FMT);
            case "Q" -> "%dQ%d".formatted(date.getYear(), quarterOf(date.getMonthValue()));
            default -> throw new IllegalArgumentException(UNSUPPORTED_PERIOD_MESSAGE + period);
        };
    }

    /**
     * 달력 월을 분기 번호로 변환한다(REQ-ECOSFMT-003) — 1~3월=1, 4~6월=2, 7~9월=3, 10~12월=4.
     *
     * @param month 1~12 범위의 달력 월
     * @return 1~4 범위의 분기 번호
     */
    static int quarterOf(int month) {
        return (month - 1) / 3 + 1;
    }
}
