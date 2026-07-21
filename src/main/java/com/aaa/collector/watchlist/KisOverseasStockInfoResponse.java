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

    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
    /** 응답 상세. */
    public record Output(
            String ovrsStckDvsnCd,
            String ovrsStckEtfRiskDrtpCd,
            String prdtEngName,
            String lstgDt,
            // ETF-specific fields for overseas ETFs
            String ovrsEtfTrgtNmixCd, // underlying index code
            String ovrsEtfChasErngRtDbnb, // leverage ratio
            // 상장폐지·거래정지 판정 필드 (REQ-WLSYNC-143). 정상 케이스(N/01)만 실측 확인됐고, 상폐/거래정지
            // 분기는 명세(api-specs/kis/18-해외주식상품기본정보.md:59-68) 기반 가설이다(§7 미해결 질문).
            String lstgAbolItemYn, // 상장폐지종목여부(Y/N)
            String ovrsStckTrStopDvsnCd) {} // 해외주식거래정지구분코드(01=정상, 02~06=각종 정지)
}
