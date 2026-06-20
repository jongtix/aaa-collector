package com.aaa.collector.stock.shortsale.overseas;

import java.math.BigDecimal;
import java.util.List;

/**
 * FINRA 수량 파싱 정적 유틸리티 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001).
 *
 * <p>{@link BigDecimal} → 비음수 무손실 {@code long} 변환 로직을 Daily/Interest 두 서비스가 공유한다. BATCH-004({@code
 * BigDecimal.longValueExact}) 무손실 변환 선례를 따른다.
 */
// @MX:NOTE: [AUTO] FINRA 수량 변환 유틸 — Daily·Interest 서비스 CBO 분산을 위해 추출한 무상태 정적 헬퍼
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
final class FinraQuantityParser {

    private FinraQuantityParser() {}

    /**
     * {@link BigDecimal} 수량을 음수 아님·무손실 {@code long}으로 변환한다. 음수·소수부가 있으면(longValueExact 실패) {@code
     * reasons}에 사유를 누적하고 {@code null}을 반환한다(BATCH-004 무손실 변환 선례 정합).
     *
     * @param value 변환 대상 수량
     * @param field 로그·사유 누적 시 표시할 필드명
     * @param reasons 검증 실패 사유 누적 리스트(호출자가 skip 판정에 사용)
     * @return 변환된 {@code long}, 또는 검증 실패 시 {@code null}
     */
    static Long toNonNegativeLong(BigDecimal value, String field, List<String> reasons) {
        if (value == null) {
            reasons.add(field + "=null");
            return null;
        }
        if (value.signum() < 0) {
            reasons.add(field + "<0(" + value.toPlainString() + ")");
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            reasons.add(field + " 소수부 존재(" + value.toPlainString() + ")");
            return null;
        }
    }
}
