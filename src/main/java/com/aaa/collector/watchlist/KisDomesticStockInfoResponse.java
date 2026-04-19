package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiResponse;

/**
 * KIS {@code search-stock-info} API 응답 DTO (주식기본조회).
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 */
public record KisDomesticStockInfoResponse(String rtCd, String msgCd, String msg1, Output output)
        implements KisApiResponse {

    /** 응답 상세. */
    public record Output(
            String sctyGrpIdCd,
            String prdtEngName,
            String sctsMketLstgDt,
            String kosdaqMketLstgDt) {}
}
