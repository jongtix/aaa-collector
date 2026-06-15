package com.aaa.collector.macro;

/**
 * 거시경제 지표 수집 결과 집계.
 *
 * @param attempted 시도 행 수
 * @param succeeded 성공(저장) 행 수
 * @param skipped skip(검증 실패·malformed) 행 수
 */
public record MacroCollectionResult(int attempted, int succeeded, int skipped) {}
