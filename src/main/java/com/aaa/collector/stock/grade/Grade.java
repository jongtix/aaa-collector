package com.aaa.collector.stock.grade;

/** 종목 투자 적합도 등급. */
public enum Grade {
    /** 우량: 상장 7년 이상 AND ADTV 시장 상위 20% */
    A,
    /** 양호: 상장 3~7년 AND ADTV 시장 상위 20%~60% */
    B,
    /** 보통: A/B 조건 미충족 또는 ETF (대표 선정 SPEC 적용 전) */
    C,
    /** 부적합: TDF/액티브 ETF 등 구조적 부적합 종목 */
    F
}
