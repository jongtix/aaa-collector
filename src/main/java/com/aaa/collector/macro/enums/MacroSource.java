package com.aaa.collector.macro.enums;

/**
 * 거시경제 지표 데이터 소스.
 *
 * <p>PRD에서 수집 대상으로 확정된 3개 소스이며, 새 소스 추가는 수집 로직 구현을 수반하는 의도적 코드 변경 사항이다.
 */
public enum MacroSource {
    /** 한국투자증권 Open API — 금리종합, 증시자금종합 */
    KIS,
    /** 한국은행 경제통계시스템(ECOS) — 기준금리, CPI, GDP, 실업률, 경상수지, 환율 */
    ECOS,
    /** 미국 연준 Federal Reserve Economic Data — 미국 기준금리, 국채, CPI, GDP, 실업률, VIX */
    FRED
}
