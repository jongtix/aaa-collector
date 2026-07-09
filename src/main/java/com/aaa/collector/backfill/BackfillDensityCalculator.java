package com.aaa.collector.backfill;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 과거 데이터 밀도 게이지 A(하한 미달)·B(내부 구멍) 순수 계산 로직 (SPEC-COLLECTOR-BACKFILL-010 REQ-BACKFILL-154/-155).
 *
 * <p>순수 로직 — KIS/Spring/DB 비의존. {@link com.aaa.collector.market.session.CoverageRefresher}가 배치 조회한
 * 종목별 스냅샷과 시장 거래일 캘린더를 받아 게이지 값을 산출한다.
 */
public final class BackfillDensityCalculator {

    private BackfillDensityCalculator() {}

    /**
     * 한 종목의 밀도 게이지 산정 입력.
     *
     * @param minTradeDate 보유 최소 거래일(도달 최과거, 데이터 없으면 null)
     * @param maxTradeDate 보유 최대 거래일(데이터 없으면 null)
     * @param rowCount 보유 행 수
     * @param trustedFloor 신뢰 가능한 기대 최과거일(신뢰 하한 부재면 {@code null} — 게이지 A 모집단에서 제외, REQ-154 MA-01)
     */
    public record StockDensityInput(
            LocalDate minTradeDate, LocalDate maxTradeDate, int rowCount, LocalDate trustedFloor) {}

    /**
     * 게이지 A(하한 미달 종목 수)를 계산한다 (REQ-154).
     *
     * <p>신뢰 가능한 하한(trustedFloor != null)이 있는 종목만 모집단에 포함하고, 그중 {@code minTradeDate > trustedFloor}인
     * 종목 수를 센다. 신뢰 하한이 없거나 데이터가 없는 종목은 영구 오탐을 피하기 위해 제외한다.
     *
     * @param stocks 종목별 밀도 산정 입력
     * @return 하한 미달 종목 수
     */
    public static long countBelowFloor(Collection<StockDensityInput> stocks) {
        return stocks.stream()
                .filter(s -> s.trustedFloor() != null && s.minTradeDate() != null)
                .filter(s -> s.minTradeDate().isAfter(s.trustedFloor()))
                .count();
    }

    /**
     * 게이지 B(내부 구멍 보유 종목 수)를 계산한다 (REQ-155).
     *
     * <p>거래일 캘린더(시장 활성 유니버스 {@code trade_date} 합집합)에서 각 종목의 {@code [minTradeDate, maxTradeDate]} 구간
     * 안에 속하는 날짜 수가 그 종목의 보유 행 수보다 많으면 구멍이 있다고 판정한다 — 거래정지일은 {@code volume=0} 행으로 저장되므로 보유 행 수 자체가
     * 구간 내 실제 거래일 수와 같아야 정상이다(전 종목 공통 부재일만 캘린더에서 빠짐, §A10).
     *
     * @param stocks 종목별 밀도 산정 입력
     * @param sortedCalendar 오름차순 정렬된 시장 거래일 캘린더(합집합)
     * @return 내부 구멍 보유 종목 수
     */
    public static long countInternalGaps(
            Collection<StockDensityInput> stocks, List<LocalDate> sortedCalendar) {
        return stocks.stream().filter(s -> hasInternalGap(s, sortedCalendar)).count();
    }

    private static boolean hasInternalGap(StockDensityInput s, List<LocalDate> sortedCalendar) {
        if (s.minTradeDate() == null || s.maxTradeDate() == null) {
            return false;
        }
        int calendarCountInRange = countInRange(sortedCalendar, s.minTradeDate(), s.maxTradeDate());
        return calendarCountInRange > s.rowCount();
    }

    /** [from, to] 폐구간에 속하는 정렬된 캘린더 날짜 수를 이진 탐색으로 계산한다. */
    private static int countInRange(List<LocalDate> sortedCalendar, LocalDate from, LocalDate to) {
        int lo = lowerBound(sortedCalendar, from);
        int hi = upperBound(sortedCalendar, to);
        return Math.max(0, hi - lo);
    }

    /** {@code from} 이상인 첫 인덱스(≥). */
    private static int lowerBound(List<LocalDate> sortedCalendar, LocalDate from) {
        int idx = Collections.binarySearch(sortedCalendar, from);
        return idx >= 0 ? idx : -(idx + 1);
    }

    /** {@code to} 이하인 마지막 인덱스 + 1(배타적 상한). */
    private static int upperBound(List<LocalDate> sortedCalendar, LocalDate to) {
        int idx = Collections.binarySearch(sortedCalendar, to);
        return idx >= 0 ? idx + 1 : -(idx + 1);
    }
}
