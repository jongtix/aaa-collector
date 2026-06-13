package com.aaa.collector.stock.supply;

import java.math.BigDecimal;

/**
 * 수급 공통 검증 헬퍼 (공매도·신용잔고 공용).
 *
 * <p>비율 컬럼은 {@code DECIMAL(7,4)} (정수부 3자리, 절댓값 < 1000) 범위만 수용한다. 자릿수 초과 값이 DB 저장 오류를 일으키지 않도록 건별
 * 검증에 사용한다 (REQ-BATCH2-060 M-2).
 */
final class SupplyDemandValidator {

    /** DECIMAL(7,4) 정수부 상한 (절댓값 < 1000 = 1000% 미만). */
    private static final BigDecimal RATE_ABS_BOUND = new BigDecimal("1000");

    private SupplyDemandValidator() {}

    /**
     * 비율 값이 {@code DECIMAL(7,4)} 표현 범위(절댓값 < 1000) 내인지 검사한다.
     *
     * @param rate 검사할 비율 (null이면 false — 저장 제외)
     * @return 절댓값 < 1000 이면 true
     */
    static boolean isRateWithinBounds(BigDecimal rate) {
        return rate != null && rate.abs().compareTo(RATE_ABS_BOUND) < 0;
    }

    /**
     * 모든 비율이 {@code DECIMAL(7,4)} 범위 내인지 검사한다.
     *
     * @param rates 검사할 비율들
     * @return 하나라도 범위를 벗어나면 false
     */
    static boolean allRatesWithinBounds(BigDecimal... rates) {
        for (BigDecimal rate : rates) {
            if (!isRateWithinBounds(rate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 값 중 하나라도 음수인지 검사한다 (음수 비정상 컬럼 검증용).
     *
     * @param values 검사할 값들
     * @return 하나라도 음수면 true
     */
    static boolean anyNegative(long... values) {
        for (long value : values) {
            if (value < 0) {
                return true;
            }
        }
        return false;
    }
}
