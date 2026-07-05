package com.aaa.collector.observability;

/**
 * 워터마크 게이지 canonical 시리즈 사전 (SPEC-OBSV-WATERMARK-001 REQ-WM-012, §3).
 *
 * <p>{@code aaa_collector_data_watermark_seconds}의 {@code series} 라벨은 아래 17개 값만 사용한다 — 사전 외 임의 라벨값
 * 생성 금지(창작 금지). {@code corp_code}는 시계열이 아니므로 이 사전에서 제외한다(MI-07, 실행 신선도 {@code batch} 라벨로만 존재).
 */
// @MX:ANCHOR: [AUTO] 워터마크 게이지 series 라벨 canonical 사전 — WatermarkMetrics·WatermarkWarmStarter에서
// fan_in >= 3
// @MX:REASON: SPEC-OBSV-WATERMARK-001 REQ-WM-012 — 17 시계열 고정, 카디널리티 가드
// @MX:SPEC: SPEC-OBSV-WATERMARK-001
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // domestic/overseas 태그값 반복 — enum 상수 정의 특성상 불가피
public enum WatermarkSeries {
    DAILY_OHLCV_KRX("daily-ohlcv-krx", "domestic"),
    DAILY_OHLCV_US("daily-ohlcv-us", "overseas"),
    INVESTOR_TREND("investor-trend", "domestic"),
    CREDIT_BALANCE("credit-balance", "domestic"),
    SHORT_SALE_DOMESTIC("short-sale-domestic", "domestic"),
    SHORT_SALE_OVERSEAS_DAILY("short-sale-overseas-daily", "overseas"),
    SHORT_SALE_OVERSEAS_INTEREST("short-sale-overseas-interest", "overseas"),
    MARKET_USDKRW("market-usdkrw", "domestic"),
    MARKET_VIX("market-vix", "overseas"),
    MACRO_ECOS("macro-ecos", "domestic"),
    MACRO_FRED("macro-fred", "overseas"),
    ANALYST_ESTIMATES("analyst-estimates", "domestic"),
    DISCLOSURES("disclosures", "domestic"),
    NEWS_DOMESTIC("news-domestic", "domestic"),
    NEWS_OVERSEAS("news-overseas", "overseas"),
    EXTENDED_HOURS_PRE("extended-hours-pre", "overseas"),
    EXTENDED_HOURS_AFTER("extended-hours-after", "overseas");

    private final String label;
    private final String marketLabel;

    WatermarkSeries(String label, String marketLabel) {
        this.label = label;
        this.marketLabel = marketLabel;
    }

    /** 게이지 {@code series} 태그 값. */
    public String seriesLabel() {
        return label;
    }

    /** 게이지 {@code market} 태그 값 ({@code domestic}/{@code overseas}). */
    public String market() {
        return marketLabel;
    }
}
