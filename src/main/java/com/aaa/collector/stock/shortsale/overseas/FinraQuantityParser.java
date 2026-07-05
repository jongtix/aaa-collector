package com.aaa.collector.stock.shortsale.overseas;

import java.math.BigDecimal;
import java.util.List;

/**
 * FINRA 수량 파싱 정적 유틸리티 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001, DECIMAL 전환
 * SPEC-COLLECTOR-SHORTSALE-DECIMAL-001).
 *
 * <p>FINRA가 2026-02-23부로 Daily 수량 필드를 소수(최대 6자리) 정밀도로 항구 전환함에 따라, 무손실 정수화 전제를 제거하고 소수를 보존하는 변환을
 * 제공한다. 음수·scale(6) 초과만 거부(fail-loud)하며 소수부는 무손실 보존한다(REQ-SSD-006/010/011/012).
 *
 * <ul>
 *   <li>{@link #toNonNegativeDecimal}: Daily 거래량용 — 소수 6자리까지 무손실 {@link BigDecimal} 반환.
 *   <li>{@link #toNonNegativeInteger}: Short Interest(잔고, 여전히 {@code BIGINT}) 정수 검증 래퍼 — 소수부가
 *       있으면(현재는 미발생 희소 조건) 조용히 버리지 않고 사유를 누적해 거부 신호로 드러낸다(REQ-SSD-016).
 * </ul>
 */
// @MX:NOTE: [AUTO] FINRA 수량 변환 유틸 — Daily(소수 보존)·Interest(정수 검증 래퍼) 서비스가 공유하는 무상태 정적 헬퍼
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-DECIMAL-001
final class FinraQuantityParser {

    /** 저장 컬럼 scale — {@code short_volume}/{@code total_volume} DECIMAL(20,6). */
    static final int MAX_SCALE = 6;

    private FinraQuantityParser() {}

    /**
     * {@link BigDecimal} 수량을 비음수·무손실 십진 표현으로 변환한다. 음수 또는 소수 자릿수가 {@value #MAX_SCALE}를 초과하면
     * fail-loud로 거부하여 {@code reasons}에 사유를 누적하고 {@code null}을 반환한다(조용한 반올림·절삭 금지, REQ-SSD-011). 소수부
     * 자체는 거부 사유가 아니며 원천 정밀도 그대로 보존한다(REQ-SSD-006/012).
     *
     * @param value 변환 대상 수량
     * @param field 로그·사유 누적 시 표시할 필드명
     * @param reasons 검증 실패 사유 누적 리스트(호출자가 skip·거부 계측에 사용)
     * @return 무손실 {@link BigDecimal}, 또는 검증 실패 시 {@code null}
     */
    static BigDecimal toNonNegativeDecimal(BigDecimal value, String field, List<String> reasons) {
        if (value == null) {
            reasons.add(field + "=null");
            return null;
        }
        if (value.signum() < 0) {
            reasons.add(field + "<0(" + value.toPlainString() + ")");
            return null;
        }
        // 유효 소수 자릿수(후행 0 제거 후 scale)가 컬럼 scale을 초과하면 무손실 저장 불가 → fail-loud 거부(REQ-SSD-011)
        if (value.stripTrailingZeros().scale() > MAX_SCALE) {
            reasons.add(field + " scale 초과(" + value.toPlainString() + ")");
            return null;
        }
        return value;
    }

    /**
     * {@link BigDecimal} 수량을 비음수 무손실 {@code long}으로 변환한다(Short Interest 잔고 전용 — {@code
     * short_interest}는 대조군 전건 정수로 {@code BIGINT} 유지). {@link #toNonNegativeDecimal} 검증(음수·scale
     * 초과)에 더해, 소수부가 있으면(현재는 미발생하는 희소 조건) 조용히 버리지 않고 사유를 누적해 거부 신호로 드러낸다(REQ-SSD-016).
     *
     * @param value 변환 대상 수량
     * @param field 로그·사유 누적 시 표시할 필드명
     * @param reasons 검증 실패 사유 누적 리스트
     * @return 무손실 {@code long}, 또는 검증 실패(음수·scale 초과·소수부 존재) 시 {@code null}
     */
    static Long toNonNegativeInteger(BigDecimal value, String field, List<String> reasons) {
        BigDecimal decimal = toNonNegativeDecimal(value, field, reasons);
        if (decimal == null) {
            return null;
        }
        try {
            return decimal.longValueExact();
        } catch (ArithmeticException e) {
            // 소수부 존재 — SI에 소수가 도입된 희소 조건. 침묵 skip이 아니라 거부 신호로 드러낸다(REQ-SSD-016).
            reasons.add(field + " 정수 아님·소수부 존재(" + decimal.toPlainString() + ")");
            return null;
        }
    }
}
