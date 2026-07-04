package com.aaa.collector.stock;

/**
 * 배당 일정 수집 결과 집계.
 *
 * <p>{@code skippedNonWatchlist}는 종목별 조회 전환(REQ-DIVFIX-010) 이후 소멸했다 — 종목별 조회는 관심종목만 질의하므로 비관심종목 행이
 * 존재하지 않는다(REQ-DIVFIX-015).
 *
 * @param attempted 시도 행 수 (전체 output1 행)
 * @param succeeded 성공(저장) 행 수
 * @param skippedUnconfirmed 미확정(cash_amount·cash_rate 둘 다 0)으로 defer된 행 수 (REQ-DIVFIX-020/050,
 *     RD-6)
 * @param skippedValidation 검증 실패로 skip된 행 수
 */
public record DividendCollectionResult(
        int attempted, int succeeded, int skippedUnconfirmed, int skippedValidation) {}
