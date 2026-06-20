package com.aaa.collector.backfill;

/**
 * 백필 종료 규칙 그룹 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>data_table별 종료 규칙 분류. [CR-02] {@code short_sale_domestic}은 그룹 B다 — 100건-미만 종료 규칙을 적용하지
 * 않는다(AC-2.4).
 */
public enum BackfillGroup {

    /** {@code daily_ohlcv}(국내·해외) 전용. 0건 또는 100건-미만(KIS 단일 호출 상한 미충족) → 종료. */
    GROUP_A,

    /**
     * {@code short_sale_domestic}·{@code investor_trend}·{@code credit_balance}. 0건 또는 연속 N회 무전진 →
     * 종료.
     */
    GROUP_B;

    /**
     * data_table명을 종료 규칙 그룹으로 분류한다.
     *
     * <p>[CR-02] {@code daily_ohlcv}만 그룹 A. {@code short_sale_domestic}은 그룹 B로 분류해 100건-미만 종료 규칙에서
     * 제외한다.
     *
     * @param dataTable data_table명
     * @return 종료 규칙 그룹
     */
    public static BackfillGroup ofDataTable(String dataTable) {
        return "daily_ohlcv".equals(dataTable) ? GROUP_A : GROUP_B;
    }
}
