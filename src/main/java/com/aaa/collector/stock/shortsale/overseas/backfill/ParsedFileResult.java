package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.util.List;

/**
 * {@link FinraCdnFileParser} 파일 단위 파싱 결과 (SPEC-COLLECTOR-BACKFILL-008 T3, REQ-BACKFILL-107).
 *
 * @param rows 유효 행 목록(요약 행·빈값·음수·소수부 제외)
 * @param skippedCount skip된 행 수(요약 행·빈값·음수·소수부·컬럼 부족 합산, 관측성 REQ-BACKFILL-123)
 */
public record ParsedFileResult(List<ParsedRow> rows, int skippedCount) {

    /** 방어적 불변 복사 — 외부 mutable 리스트 참조 노출 방지(SpotBugs EI_EXPOSE_REP2). */
    public ParsedFileResult {
        rows = List.copyOf(rows);
    }
}
