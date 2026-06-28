package com.aaa.collector.stock;

import java.time.LocalDate;
import java.util.List;

/**
 * 액면교체(SPLIT) 종목지정 백필 fetch 단계 결과 DTO (SPEC-COLLECTOR-BACKFILL-007 W5, D-OUTCOME-ADAPTER).
 *
 * <p>국내 일봉 백필의 {@link com.aaa.collector.stock.daily.DomesticDailyOhlcvFetch}와 대칭으로, {@code
 * BackfillWindowExecutor.routePersist} switch의 타입 매칭 대상이다. fetch 단계에서 매핑·degenerate skip을 마친 적재 대상
 * 엔티티를 담아 persist 단계가 재매핑 없이 INSERT IGNORE로 적재한다.
 *
 * <p>[HARD] REQ-BACKFILL-099a — 종료 판정 결정성: 두 행수를 명시적으로 분리한다.
 *
 * <ul>
 *   <li>{@code rawRowCount}: KIS {@code output1} 원본 응답 행수(degenerate skip 적용 <b>전</b>). 그룹 A 종료 판정
 *       입력 — degenerate가 섞여도 입력에 따라 일의적으로 결정된다(REQ-BACKFILL-099/099a).
 *   <li>{@code validRows}: degenerate skip(`af<=0 OR bf<=0 OR af==bf`)을 통과해 실제 적재되는 엔티티. {@code
 *       validRows.size()}는 종료 판정과 분리된 저장 행수다.
 * </ul>
 *
 * <p>{@code corporate_events}는 종목당 이벤트 ≪ 100이라 {@code rawRowCount}·{@code validRows.size()} 모두 100건
 * 미만이므로 종료 결과(첫 윈도우 GROUP_A COMPLETED)는 두 값의 발산과 무관하게 불변이다.
 *
 * @param validRows 적재 대상(검증 통과) 엔티티 — degenerate skip 적용 후
 * @param oldestRecordDate 적재 대상 행들의 최소 {@code record_date}(event_date), 적재 대상 없으면 {@code null}
 * @param rawRowCount KIS {@code output1} 원본 응답 행수(skip 전) — 종료 판정 입력(REQ-BACKFILL-099a)
 */
public record RevSplitBackfillFetch(
        List<CorporateEvent> validRows, LocalDate oldestRecordDate, int rawRowCount) {

    public RevSplitBackfillFetch {
        validRows = List.copyOf(validRows);
    }
}
