package com.aaa.collector.backfill;

import java.util.Set;

/**
 * 백필 종료 규칙 그룹 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>data_table별 종료 규칙 분류. [CR-02] {@code short_sale_domestic}은 그룹 B다 — 100건-미만 종료 규칙을 적용하지
 * 않는다(AC-2.4).
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-007 REQ-BACKFILL-093] {@code corporate_events}(SPLIT 과거 백필)도 그룹 A다 —
 * 종목지정 조회는 종목당 0~2건이라 첫 윈도우에서 100건-미만 종료 규칙으로 즉시 COMPLETED된다.
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-009 REQ-BACKFILL-145] {@code corporate_events_dividend}(DIVIDEND 과거
 * 백필)도 그룹 A다 — 종목지정 전기간 조회는 실측상 종목당 100건 미만(6종목 전부 71행 이하)이라 첫 윈도우에서 즉시 COMPLETED된다. SPLIT과 구분되는 별도
 * {@code data_table} 논리 키로 {@code backfill_status} 진행 상태를 분리한다(RD-1).
 */
public enum BackfillGroup {

    /**
     * {@code daily_ohlcv}(국내·해외)·{@code corporate_events}·{@code corporate_events_dividend}. 0건 또는
     * 100건-미만(KIS 단일 호출 상한 미충족) → 종료.
     */
    GROUP_A,

    /**
     * {@code short_sale_domestic}·{@code investor_trend}·{@code credit_balance}. 0건 또는 연속 N회 무전진 →
     * 종료.
     */
    GROUP_B;

    /** 그룹 A 종료 규칙(100건-미만 즉시 COMPLETED) 적용 data_table 집합. */
    private static final Set<String> GROUP_A_TABLES =
            Set.of("daily_ohlcv", "corporate_events", "corporate_events_dividend");

    /**
     * data_table명을 종료 규칙 그룹으로 분류한다.
     *
     * <p>[CR-02] {@code daily_ohlcv}·{@code corporate_events}·{@code corporate_events_dividend}만 그룹
     * A. {@code short_sale_domestic}·수급 2종은 그룹 B로 분류해 100건-미만 종료 규칙에서 제외한다. (REQ-BACKFILL-093/145)
     *
     * @param dataTable data_table명
     * @return 종료 규칙 그룹
     */
    public static BackfillGroup ofDataTable(String dataTable) {
        return GROUP_A_TABLES.contains(dataTable) ? GROUP_A : GROUP_B;
    }
}
