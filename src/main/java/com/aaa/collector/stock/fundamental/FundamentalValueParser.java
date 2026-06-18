package com.aaa.collector.stock.fundamental;

import java.math.BigDecimal;

/**
 * 재무비율/투자의견 수치 필드 파싱·검증 헬퍼 (SPEC-COLLECTOR-BATCH-004 전용, REQ-BATCH4-070a, CR-1).
 *
 * <p>[HARD] {@link com.aaa.collector.stock.supply.SupplyDemandValidator}의 경계 상수({@code
 * RATE_ABS_BOUND=1000})·부호 규칙({@code anyNegative})을 <b>재사용하지 않는다</b>. 그 1000 상수·음수 거부는 수급 {@code
 * DECIMAL(7,4)} 전용이며, financials/analyst_estimates의 비율·괴리 컬럼({@code DECIMAL(12,4)}, 절댓값 < 10^8)·음수
 * 정상 증가율과 충돌한다.
 *
 * <p>본 SPEC 전용 규칙:
 *
 * <ul>
 *   <li><b>DECIMAL(12,4) 정수부 경계</b>: 절댓값 정수부가 {@code >= 10^8}이면 경계 초과 — 호출자가 행을 skip한다. 음수는 정상값이므로
 *       부호로 거부하지 않는다(절댓값 기준). 소수부 scale은 setScale/반올림 없이 그대로 보존하여 Hibernate/JDBC 컬럼 scale 변환에 위임한다.
 *   <li><b>BIGINT 매핑 필드</b>: {@code new BigDecimal(trimmed)}로 파싱 후 {@code longValueExact()}로 정수
 *       변환한다(단일 경로). {@code ".00"} 같은 0-소수부 값은 무손실 정수로 변환되고({@code "6993.00"}→6993), 비0 소수부·long 범위
 *       초과는 {@link ArithmeticException}을 던져 호출자 행 단위 try/catch에서 건별 skip되게 한다(반올림 아님). 모든 BIGINT 매핑
 *       필드 공통 규칙 — eps 전용 분기·전용 방어 없음.
 * </ul>
 */
final class FundamentalValueParser {

    /** DECIMAL(12,4) 정수부 경계 — 절댓값 정수부가 이 값 이상이면 컬럼 수용 불가 (precision 12, scale 4 → 정수부 8자리). */
    static final BigDecimal DECIMAL_INTEGER_BOUND = new BigDecimal("100000000"); // 10^8

    private FundamentalValueParser() {}

    /**
     * DECIMAL(12,4) 비율/괴리 필드를 파싱한다. 부호로 거부하지 않으며(음수 정상), 정수부 경계 초과 시 {@link ArithmeticException}을
     * 던진다(호출자 행 단위 try/catch에서 건별 skip). 소수부 scale은 보존한다(setScale/반올림 없음).
     *
     * @param raw 원문 문자열 (null 또는 blank 입력은 {@link NumberFormatException}으로 정규화 — 서비스 건별 skip 경로로
     *     라우팅)
     * @return 파싱된 값 (정수부 경계 내)
     * @throws NumberFormatException null/blank 또는 파싱 불가
     * @throws ArithmeticException 정수부 경계({@code |value| >= 10^8}) 초과
     */
    static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new NumberFormatException("null/blank numeric: " + raw);
        }
        BigDecimal value = new BigDecimal(raw.trim());
        if (value.abs().compareTo(DECIMAL_INTEGER_BOUND) >= 0) {
            throw new ArithmeticException("DECIMAL(12,4) 정수부 경계 초과 (|value| >= 10^8): " + raw);
        }
        return value;
    }

    /**
     * BIGINT 매핑 문자열 필드(eps/sps/bps/target_price/prev_close)를 무손실 정수로 변환한다 (REQ-BATCH4-070a BIGINT
     * 규칙).
     *
     * <p>{@code new BigDecimal(trimmed)}로 파싱 후 {@code longValueExact()}로 변환한다. {@code ".00"} 같은
     * 0-소수부 값은 무손실 변환되고({@code "6993.00"}→6993), 비0 소수부({@code "6993.50"})·long 범위 초과는 {@link
     * ArithmeticException}을 던진다. {@code Long.parseLong}은 {@code ".00"} 접미사에서 {@link
     * NumberFormatException}을 던져 전 행을 skip시키므로 사용하지 않는다.
     *
     * @param raw 원문 문자열 (null 또는 blank 입력은 {@link NumberFormatException}으로 정규화 — 서비스 건별 skip 경로로
     *     라우팅)
     * @return 무손실 변환된 정수
     * @throws NumberFormatException null/blank 또는 파싱 불가
     * @throws ArithmeticException 비0 소수부 또는 long 범위 초과
     */
    static long parseBigInt(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new NumberFormatException("null/blank numeric: " + raw);
        }
        return new BigDecimal(raw.trim()).longValueExact();
    }
}
