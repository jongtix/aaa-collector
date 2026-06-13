package com.aaa.collector.stock.supply;

/**
 * 수급 1종 수집 결과 집계.
 *
 * <p>일봉 {@link com.aaa.collector.stock.daily.CollectionResult}와 동일한 형태이나 수급 도메인 의미를 분리하기 위해 별도 타입으로
 * 둔다. {@code attempted = succeeded + skipped} 관계가 성립한다(REQ-BATCH2-062).
 *
 * @param attempted 시도 종목 수
 * @param succeeded 성공(빈 응답 0건 포함) 종목 수
 * @param skipped skip(호출 실패·토큰 실패 등) 종목 수
 */
public record SupplyDemandResult(int attempted, int succeeded, int skipped) {}
