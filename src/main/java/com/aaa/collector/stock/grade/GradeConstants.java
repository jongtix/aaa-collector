package com.aaa.collector.stock.grade;

/**
 * 등급 분류 공통 상수 (SPEC-COLLECTOR-GRADE-004).
 *
 * <p>GradeClassifier에서 참조하는 데이터 게이트·ADTV 절대 임계값을 단일 출처로 관리한다.
 */
// @MX:SPEC: SPEC-COLLECTOR-GRADE-004
final class GradeConstants {

    /** A 등급 후보 최소 일봉 보유 일수 (750거래일 이상). */
    static final long HOLDING_DAYS_A = 750L;

    /** B 등급 후보 최소 일봉 보유 일수 (250거래일 이상). */
    static final long HOLDING_DAYS_B = 250L;

    /** KRX A 등급 ADTV 임계값 — 500억 KRW. */
    static final double KRX_ADTV_HIGH = 5e10;

    /** KRX B 등급 ADTV 하한 임계값 — 100억 KRW. */
    static final double KRX_ADTV_LOW = 1e10;

    /** US A 등급 ADTV 임계값 — $2B USD. */
    static final double US_ADTV_HIGH = 2e9;

    /** US B 등급 ADTV 하한 임계값 — $500M USD. */
    static final double US_ADTV_LOW = 5e8;

    private GradeConstants() {
        // 상수 홀더 — 인스턴스화 금지
    }

    /**
     * 시장별 ADTV HIGH 임계값을 반환한다.
     *
     * @param market "KRX" 또는 "US"
     * @return A 등급 ADTV 임계값
     */
    static double getHighThreshold(String market) {
        return "US".equals(market) ? US_ADTV_HIGH : KRX_ADTV_HIGH;
    }

    /**
     * 시장별 ADTV LOW 임계값을 반환한다.
     *
     * @param market "KRX" 또는 "US"
     * @return B 등급 ADTV 하한 임계값
     */
    static double getLowThreshold(String market) {
        return "US".equals(market) ? US_ADTV_LOW : KRX_ADTV_LOW;
    }
}
