package com.aaa.collector.stock.fundamental;

/**
 * 종목 단위 펀더멘털 배치(재무비율/투자의견) 수집 결과 집계 (SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-072).
 *
 * <p>일봉 {@link com.aaa.collector.stock.daily.CollectionResult}·수급 {@link
 * com.aaa.collector.stock.supply.SupplyDemandResult}와 동일한 형태이나 펀더멘털 도메인 의미를 분리하기 위해 별도 타입으로 둔다.
 * {@code attempted = succeeded + skipped} 관계가 성립한다.
 *
 * <p>재무비율은 종목당 연간+분기 2회 호출하므로 attempted/succeeded/skipped는 <b>(종목, 분류구분) 호출 단위</b> 카운트다. 투자의견은 종목당
 * 1회이므로 종목 단위 카운트다.
 *
 * @param attempted 시도 단위 수 (재무: 종목×2, 투자의견: 종목)
 * @param succeeded 성공(빈 응답 0건 포함) 단위 수
 * @param skipped skip(호출 실패·토큰 실패 등) 단위 수
 */
public record FundamentalResult(int attempted, int succeeded, int skipped) {}
