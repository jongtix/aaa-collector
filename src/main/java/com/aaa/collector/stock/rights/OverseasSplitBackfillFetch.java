package com.aaa.collector.stock.rights;

import com.aaa.collector.stock.CorporateEvent;
import java.time.LocalDate;
import java.util.List;

/**
 * 해외 액면분할·병합(SPLIT) 종목지정 백필 fetch 단계 결과 DTO (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-060/061).
 *
 * <p>국내 {@link com.aaa.collector.stock.RevSplitBackfillFetch}와 대칭으로, {@code
 * BackfillWindowExecutor.routePersist} switch의 타입 매칭 대상이다. fetch 단계에서 dedup·÷100 정규화·매핑을 마친 적재 대상
 * 엔티티를 담아 persist 단계가 재매핑 없이 INSERT IGNORE로 적재한다.
 *
 * <p>종료 판정 결정성: 두 행수를 분리한다.
 *
 * <ul>
 *   <li>{@code rawRowCount}: CTRGT011R 14+15 원본 응답 행수(dedup·skip 적용 <b>전</b>). GROUP_A 종료 판정 입력.
 *   <li>{@code validRows}: dedup·정규화를 통과해 실제 적재되는 엔티티. {@code validRows.size()}는 종료 판정과 분리된 저장 행수다.
 * </ul>
 *
 * <p>{@code corporate_events}는 종목당 분할 이벤트 ≪ 100이라 두 값 모두 100건 미만이므로 첫 윈도우 GROUP_A COMPLETED가 두 값의
 * 발산과 무관하게 확정된다.
 *
 * @param validRows 적재 대상(dedup·정규화 통과) 엔티티
 * @param oldestRecordDate 적재 대상 행들의 최소 {@code event_date}, 적재 대상 없으면 {@code null}
 * @param rawRowCount CTRGT011R 14+15 원본 응답 행수(dedup 전) — 종료 판정 입력
 */
public record OverseasSplitBackfillFetch(
        List<CorporateEvent> validRows, LocalDate oldestRecordDate, int rawRowCount) {

    public OverseasSplitBackfillFetch {
        validRows = List.copyOf(validRows);
    }
}
