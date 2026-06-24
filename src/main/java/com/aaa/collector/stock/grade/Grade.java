package com.aaa.collector.stock.grade;

/** 종목 투자 적합도 등급 (SPEC-COLLECTOR-GRADE-004). */
public enum Grade {
    /** 우량: 일봉 보유 750일 이상 AND ADTV ≥ HIGH 임계값 (KRX 500억 / US $2B) */
    A,
    /** 양호: 일봉 보유 250일 이상 AND LOW ≤ ADTV < HIGH (KRX 100억~500억 / US $500M~$2B) */
    B,
    /** 보통: A/B 조건 미충족, 비대표 ETF, 또는 일봉 결손 */
    C,
    /** 부적합: TDF/액티브 ETF 등 구조적 부적합 종목 */
    F
}
