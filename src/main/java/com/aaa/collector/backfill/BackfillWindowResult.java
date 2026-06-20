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
 *   <li>{@code rowCount}: 적재 대상(검증 통과) 행 수. 그룹 A 100건-cap 종료(REQ-BACKFILL-013)·0건
 *       종료(REQ-BACKFILL-012) 판정의 입력.
 * </ul>
 *
 * <p>이 레코드는 오케스트레이터(T6)가 직전 윈도우 상태(이전 oldest·이전 행수·누적 stale)와 결합해 {@link BackfillWindowOutcome}로
 * 승격시키는 원자 입력이다 — 수집 서비스는 직전 상태를 알지 못하므로 풍부한 {@link BackfillWindowOutcome}를 직접 만들지 않는다.
 *
 * @param oldestTradeDate 적재 대상 행들의 최소 거래일 (적재 대상 없으면 {@code null})
 * @param rowCount 적재 대상 행 수 (적재 대상 없으면 0)
 */
public record BackfillWindowResult(LocalDate oldestTradeDate, int rowCount) {

    /** 적재 대상 행이 없는(0건/skip) 윈도우 결과. */
    public static final BackfillWindowResult EMPTY = new BackfillWindowResult(null, 0);
}
