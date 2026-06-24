package com.aaa.collector.news.overseas;

/**
 * 해외 뉴스 제목 수집 결과 집계 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * @param attempted 시도(파싱 대상) 행 수 — 중복 dedup 포함 응답 행 누계
 * @param succeeded 멱등 삽입에 성공한(신규 저장) 행 수
 * @param skipped 검증 실패(news_key/data_dt null)·중복 dedup·독성 행으로 저장하지 않은 행 수
 */
public record OverseasNewsCollectionResult(int attempted, int succeeded, int skipped) {}
