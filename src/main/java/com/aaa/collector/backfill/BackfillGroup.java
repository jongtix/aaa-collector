package com.aaa.collector.backfill;

import java.util.Set;

/**
 * 백필 종료 규칙 그룹 (SPEC-COLLECTOR-BACKFILL-001 T5).
 *
 * <p>data_table별 종료 규칙 분류. [CR-02] {@code short_sale_domestic}은 그룹 B다 — 100건-미만 종료 규칙을 적용하지
 * 않는다(AC-2.4).
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-002/006] {@code corporate_events}(SPLIT 과거 백필)는 더
 * 이상 그룹 A가 아니라 그룹 C다. 원래 SPEC-COLLECTOR-BACKFILL-007 REQ-BACKFILL-093은 "종목지정 조회는 종목당 0~2건이라 첫 윈도우에서
 * 100건-미만 종료 규칙으로 즉시 COMPLETED된다"고 가정했으나, aaa-infra#82가 이를 반증했다 — KIS {@code CTRGT011R}의 {@code
 * PDNO}는 정확 일치가 아니라 접두어 매칭이라(예: {@code U}(Unity)·{@code V}(Visa)) 짧은 접두어 종목은 노이즈만으로 100건을 넘겨
 * GROUP_A 100건-cap 종료 규칙 자체가 범주 오류로 오작동한다(영구 미완료 또는 anchor 무전진 조기중단). 이 종료 규칙은 "응답당 100건 캡 + 다음 페이지
 * 존재" 구조(daily_ohlcv)를 전제하는데 corporate_events는 페이지 개념이 없는 커서완주(해외)·단일콜(국내) 구조라 행수를 종료 신호로 쓸 수 없다.
 * 대신 fetch 성공 자체가 소진 증거인 그룹 C로 이관됐다.
 *
 * <p>[SPEC-COLLECTOR-BACKFILL-009 REQ-BACKFILL-145] {@code corporate_events_dividend}(DIVIDEND 과거
 * 백필)는 그룹 A로 유지된다 — 종목지정 전기간 조회는 실측상 종목당 100건 미만(6종목 전부 71행 이하)이라 첫 윈도우에서 즉시 COMPLETED된다. 배당은 종목지정
 * 조회라 접두어 노이즈가 없어 이 종료 규칙 자체는 유효하다(REQ-GC-005) — SPLIT과 달리 그룹 C로 이관하지 않는다. SPLIT과 구분되는 별도 {@code
 * data_table} 논리 키로 {@code backfill_status} 진행 상태를 분리한다(RD-1).
 */
public enum BackfillGroup {

    /**
     * {@code daily_ohlcv}(국내·해외)·{@code corporate_events_dividend}. 0건 또는 100건-미만(KIS 단일 호출 상한 미충족)
     * → 종료. {@code corporate_events}(SPLIT)는 GROUP_C로 이관됐다(SPEC-COLLECTOR-BACKFILL-GROUPC-001
     * REQ-GC-002).
     */
    GROUP_A,

    /**
     * {@code short_sale_domestic}·{@code investor_trend}·{@code credit_balance}. 0건 또는 연속 N회 무전진 →
     * 종료.
     */
    GROUP_B,

    /**
     * {@code corporate_events}(SPLIT, 시장무관). 커서완주(해외) 또는 페이징 구조적 불가 단일콜(국내)의 fetch 성공 자체가 소진 증거 —
     * 행수 무관 무조건 종료(SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-001/004).
     */
    GROUP_C;

    /** 그룹 A 종료 규칙(100건-미만 즉시 COMPLETED) 적용 data_table 집합. */
    private static final Set<String> GROUP_A_TABLES =
            Set.of("daily_ohlcv", "corporate_events_dividend");

    /**
     * 그룹 C(커서완주·단일콜형) 적용 data_table 집합 (SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-002). {@code
     * corporate_events}(SPLIT, 시장무관)만 해당 — {@code corporate_events_dividend}(배당)는 GROUP_A
     * 유지(REQ-GC-005).
     */
    private static final Set<String> GROUP_C_TABLES = Set.of("corporate_events");

    /**
     * data_table명을 종료 규칙 그룹으로 분류한다.
     *
     * <p>[SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-002] {@code corporate_events}(SPLIT)는 그룹 C로
     * 이관됐다 (#82 접두어 노이즈로 인한 GROUP_A 100건-cap 종료 오판 방지). {@code daily_ohlcv}·{@code
     * corporate_events_dividend}는 그룹 A 유지. {@code short_sale_domestic}·수급 2종은 그룹 B(100건-미만 종료 규칙
     * 미적용). (REQ-BACKFILL-093/145, REQ-GC-002/005)
     *
     * @param dataTable data_table명
     * @return 종료 규칙 그룹
     */
    public static BackfillGroup ofDataTable(String dataTable) {
        if (GROUP_C_TABLES.contains(dataTable)) {
            return GROUP_C;
        }
        if (GROUP_A_TABLES.contains(dataTable)) {
            return GROUP_A;
        }
        return GROUP_B;
    }
}
