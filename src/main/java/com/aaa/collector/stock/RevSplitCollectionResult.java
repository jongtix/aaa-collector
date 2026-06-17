package com.aaa.collector.stock;

/**
 * 액면교체 수집 결과 집계 (REQ-BATCH5-072).
 *
 * <p>선례 {@link DividendCollectionResult} 4항 record 무변경 답습. {@code degenerate}({@code af<=0 OR
 * bf<=0}) 및 무변동({@code af==bf}) skip은 {@code skippedValidation}에 흡수한다(MA-02 — 전용 5번째 카운터 없음).
 *
 * <p>불변식: {@code attempted = succeeded + skippedNonWatchlist + skippedValidation}.
 *
 * @param attempted 시도 행 수 (전체 output1 행)
 * @param succeeded 성공(저장) 행 수
 * @param skippedNonWatchlist 비관심종목으로 skip된 행 수
 * @param skippedValidation 검증 실패(null 키·날짜 파싱·degenerate·무변동·경계 초과)로 skip된 행 수
 */
public record RevSplitCollectionResult(
        int attempted, int succeeded, int skippedNonWatchlist, int skippedValidation) {}
