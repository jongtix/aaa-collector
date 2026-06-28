package com.aaa.collector.backfill;

import java.time.LocalDate;

/**
 * 백필 윈도우 1구간의 수집 결과 — 수집 서비스 계층이 노출하는 최소 값 객체 (SPEC-COLLECTOR-BACKFILL-001 T3/T4).
 *
 * <p>수집 서비스의 윈도우 메서드(예: {@code DomesticDailyOhlcvCollectionService.collectWindow})가 당일 수집과 동일한
 * 검증·매핑·INSERT IGNORE 경로(REQ-BACKFILL-002)를 거친 뒤, 종료 판정에 필요한 두 신호만 담아 반환한다.
 *
 * <ul>
 *   <li>{@code oldestTradeDate}: 적재 대상(검증 통과) 행들의 최소(가장 과거) 거래일. 다음 anchor 전진(REQ-BACKFILL-015)과 그룹
 *       B 진행 정체 판정(REQ-BACKFILL-014)의 입력. 적재 대상 행이 없으면 {@code null}.
 *   <li>{@code rowCount}: 적재 대상(검증 통과) 행 수. 0건 종료(REQ-BACKFILL-012)·메트릭·{@code last_row_count}
 *       영속·그룹 B clamp의 입력(의미 보존).
 *   <li>{@code rawRowCount}: KIS 원본 응답 행수(거부 전). 그룹 A 100건-cap 종료(REQ-BACKFILL-013) 전용 입력. 그룹 B는
 *       {@code rawRowCount = rowCount}로 기본 채워 동작을 불변으로 유지한다(SPEC-COLLECTOR-BACKFILL-006).
 * </ul>
 *
 * <p>이 레코드는 오케스트레이터(T6)가 직전 윈도우 상태(이전 oldest·이전 행수·누적 stale)와 결합해 {@link BackfillWindowOutcome}로
 * 승격시키는 원자 입력이다 — 수집 서비스는 직전 상태를 알지 못하므로 풍부한 {@link BackfillWindowOutcome}를 직접 만들지 않는다.
 *
 * @param oldestTradeDate 적재 대상 행들의 최소 거래일 (적재 대상 없으면 {@code null})
 * @param rowCount 적재 대상(저장) 행 수 (적재 대상 없으면 0)
 * @param rawRowCount KIS 원본 응답 행수 — 그룹 A 종료 전용. 그룹 B·레거시 경로는 {@code rowCount}와 동일
 */
// @MX:NOTE: [AUTO] rawRowCount = GROUP_A 종료 전용. GROUP_B 생산자·EMPTY는 rawRowCount=rowCount 기본.
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-006 REQ-BACKFILL-081,-088 — GROUP_B 종료·clamp·메트릭 불변 보장.
public record BackfillWindowResult(LocalDate oldestTradeDate, int rowCount, int rawRowCount) {

    /** 적재 대상 행이 없는(0건/skip) 윈도우 결과. */
    public static final BackfillWindowResult EMPTY = new BackfillWindowResult(null, 0, 0);

    /**
     * 그룹 B·레거시 경로용 — {@code rawRowCount}를 {@code rowCount}와 동일하게 설정하는 편의 생성자.
     *
     * <p>그룹 A 종료 판정에서만 {@code rawRowCount}가 의미를 가지므로, {@code rawRowCount}를 별도로 산정하지 않는
     * 생산자(투자자동향·신용·공매도 및 daily_ohlcv {@code collectWindow} 레거시 경로)는 이 생성자로 동작을 불변 유지한다.
     *
     * @param oldestTradeDate 적재 대상 행들의 최소 거래일
     * @param rowCount 적재 대상(저장) 행 수 — {@code rawRowCount}로도 동일 사용
     */
    public BackfillWindowResult(LocalDate oldestTradeDate, int rowCount) {
        this(oldestTradeDate, rowCount, rowCount);
    }
}
