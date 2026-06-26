package com.aaa.collector.schedule;

/**
 * 8개 배치 단위의 cron·zone 공유 상수 (SPEC-COLLECTOR-CATCHUP-001 T1).
 *
 * <p>단일 소스 원칙: {@code @Scheduled} 어노테이션과 {@link com.aaa.collector.schedule.catchup.CatchUpRunner}
 * 레지스트리가 동일 값을 참조하여 드리프트를 원천 제거한다(AC-14).
 *
 * <p>인스턴스화 불필요 — 모든 상수는 {@code public static final String}.
 */
public final class BatchCrons {

    private BatchCrons() {}

    // ─── domestic-daily-chain ────────────────────────────────────────────────
    /** 국내 일봉 배치 cron — 평일 16:00 KST. */
    public static final String DOMESTIC_DAILY_CHAIN_CRON = "0 0 16 * * MON-FRI";

    /** 국내 일봉 배치 zone. */
    public static final String DOMESTIC_DAILY_CHAIN_ZONE = "Asia/Seoul";

    // ─── overseas-daily ──────────────────────────────────────────────────────
    /** 미국 일봉 배치 cron — 평일 16:30 ET. */
    public static final String OVERSEAS_DAILY_CRON = "0 30 16 * * MON-FRI";

    /** 미국 일봉 배치 zone. */
    public static final String OVERSEAS_DAILY_ZONE = "America/New_York";

    // ─── overseas-shortsale ──────────────────────────────────────────────────
    /** 미국 공매도 배치 cron — 평일 10:00 ET. */
    public static final String OVERSEAS_SHORTSALE_CRON = "0 0 10 * * MON-FRI";

    /** 미국 공매도 배치 zone. */
    public static final String OVERSEAS_SHORTSALE_ZONE = "America/New_York";

    // ─── domestic-invest-opinion ─────────────────────────────────────────────
    /** 투자의견 배치 cron — 평일 18:00 KST. */
    public static final String DOMESTIC_INVEST_OPINION_CRON = "0 0 18 * * MON-FRI";

    /** 투자의견 배치 zone. */
    public static final String DOMESTIC_INVEST_OPINION_ZONE = "Asia/Seoul";

    // ─── domestic-financial-ratio ────────────────────────────────────────────
    /** 재무비율 배치 cron — 토요일 08:00 KST (주1회). */
    public static final String DOMESTIC_FINANCIAL_RATIO_CRON = "0 0 8 * * SAT";

    /** 재무비율 배치 zone. */
    public static final String DOMESTIC_FINANCIAL_RATIO_ZONE = "Asia/Seoul";

    // ─── macro-external ──────────────────────────────────────────────────────
    /** 외부 거시경제 지표 배치 cron — 평일 19:00 KST. */
    public static final String MACRO_EXTERNAL_CRON = "0 0 19 * * MON-FRI";

    /** 외부 거시경제 지표 배치 zone. */
    public static final String MACRO_EXTERNAL_ZONE = "Asia/Seoul";

    // ─── market-indicators ───────────────────────────────────────────────────
    /** 시장지표 묶음 배치 cron — 평일 17:00 KST. */
    public static final String MARKET_INDICATORS_CRON = "0 0 17 * * MON-FRI";

    /** 시장지표 묶음 배치 zone. */
    public static final String MARKET_INDICATORS_ZONE = "Asia/Seoul";

    // ─── domestic-etf-representative ─────────────────────────────────────────
    /** ETF 대표 종목 재계산 배치 cron — 매주 월요일 07:50 KST. */
    public static final String DOMESTIC_ETF_REPRESENTATIVE_CRON = "0 50 7 * * MON";

    /** ETF 대표 종목 재계산 배치 zone. */
    public static final String DOMESTIC_ETF_REPRESENTATIVE_ZONE = "Asia/Seoul";
}
