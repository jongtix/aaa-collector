package com.aaa.collector.stock.rights;

/**
 * 해외 현금배당 수집 집계 결과 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * @param attemptedStocks 조회를 시도한 종목 수
 * @param succeededRows {@code corporate_events}에 멱등 삽입을 시도한(저장 대상) 현금배당 행 수
 * @param skippedStocks 빈 응답·전 키 사망 등으로 종목 단위 skip된 수
 * @param skippedNonCashRows 비현금배당(증자·상폐 등)이라 저장하지 않고 skip한 행 수 (REQ-OVE-023a)
 * @param skippedValidationRows 필수 날짜 null·파싱 실패·독성 행으로 skip한 행 수
 */
public record OverseasRightsCollectionResult(
        int attemptedStocks,
        int succeededRows,
        int skippedStocks,
        int skippedNonCashRows,
        int skippedValidationRows) {}
