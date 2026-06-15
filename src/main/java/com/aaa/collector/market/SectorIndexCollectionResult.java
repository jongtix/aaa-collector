package com.aaa.collector.market;

/**
 * 업종지수 수집 결과 집계.
 *
 * @param attempted 시도 종목 수 (대상 INDEX 코드 수)
 * @param succeeded 성공(API 호출 완료) 종목 수
 * @param skipped skip(INDEX 행 부재·API 예외) 종목 수
 */
public record SectorIndexCollectionResult(int attempted, int succeeded, int skipped) {}
