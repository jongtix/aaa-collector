package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiResponse;

/**
 * KIS {@code search-info} API 응답 DTO (해외주식 상품기본정보).
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 JSON snake_case 키가
 * camelCase 필드에 자동 매핑된다.
 */
public record KisOverseasStockInfoResponse(String rtCd, String msgCd, String msg1, Output output)
        implements KisApiResponse {

    /** 응답 상세. */
    public record Output(
            String ovrsStckDvsnCd,
            String ovrsStckEtfRiskDrtpCd,
            String prdtEngName,
            String lstgDt,
            // ETF-specific fields for overseas ETFs
            String ovrsEtfTrgtNmixCd, // underlying index code
            String ovrsEtfChasErngRtDbnb) {} // leverage ratio
}
