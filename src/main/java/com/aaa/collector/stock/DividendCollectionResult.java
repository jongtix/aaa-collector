package com.aaa.collector.stock;

/**
 * 배당 일정 수집 결과 집계.
 *
 * @param attempted 시도 행 수 (전체 output1 행)
 * @param succeeded 성공(저장) 행 수
 * @param skippedNonWatchlist 비관심종목으로 skip된 행 수
 * @param skippedValidation 검증 실패로 skip된 행 수
 */
public record DividendCollectionResult(
        int attempted, int succeeded, int skippedNonWatchlist, int skippedValidation) {}
