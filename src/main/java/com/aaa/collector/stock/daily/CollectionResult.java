package com.aaa.collector.stock.daily;

/**
 * 국내 일봉 수집 결과 집계.
 *
 * @param attempted 시도 종목 수
 * @param succeeded 성공(저장) 종목 수
 * @param skipped skip(실패) 종목 수
 */
public record CollectionResult(int attempted, int succeeded, int skipped) {}
