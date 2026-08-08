package com.aaa.collector.stock.rights;

import com.aaa.collector.stock.CorporateEvent;
import java.time.LocalDate;
import java.util.List;

/**
 * 해외 현금배당 종목지정 백필 fetch 단계 결과 DTO (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-054).
 *
 * <p>{@link OverseasSplitBackfillFetch}와 동일 3필드 구조(validRows/oldest/rawRowCount) — {@code
 * BackfillWindowExecutor.routePersist}의 switch 패턴 매칭 대상.
 *
 * <p>{@code rawRowCount}는 {@code rights-by-ice}의 모든 서브윈도우 청크(REQ-ODW-051a)에 걸친 {@code output1} 원본
 * 응답 행수 합산이다(defer 이전, 청크 경계 중복 포함 — 필터링 이전 원본값, REQ-ODW-054).
 *
 * @param validRows 적재 대상(현금배당 판정·필수 필드 검증·CTRGT011R 확정 매칭 통과) 엔티티
 * @param oldestRecordDate 적재 대상 행들의 최소 {@code event_date}, 적재 대상 없으면 {@code null}
 * @param rawRowCount rights-by-ice 전체 서브윈도우 청크 원본 응답 행수 합산(dedup·필터 전) — 종료 판정 참조 안 함(GROUP_C)
 */
public record OverseasDividendBackfillFetch(
        List<CorporateEvent> validRows, LocalDate oldestRecordDate, int rawRowCount) {

    public OverseasDividendBackfillFetch {
        validRows = List.copyOf(validRows);
    }
}
