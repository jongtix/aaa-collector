package com.aaa.collector.news;

/**
 * 뉴스 제목 수집 결과 집계.
 *
 * @param attempted 시도 행 수
 * @param succeeded 성공(저장) 행 수
 * @param skipped skip(검증 실패·null serial_no) 행 수
 */
public record NewsCollectionResult(int attempted, int succeeded, int skipped) {}
