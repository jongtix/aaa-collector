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

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001, SPEC-COLLECTOR-WLSYNC-008
    /** 응답 상세. */
    public record Output(
            String sctyGrpIdCd,
            String prdtEngName,
            String sctsMketLstgDt,
            String kosdaqMketLstgDt,
            // 시장ID코드 — KOSPI/KOSDAQ 권위 판정 필드 (REQ-STOCKMETA-002)
            // STK=유가증권(KOSPI), KSQ=코스닥(KOSDAQ)
            // api-specs/kis/17-주식기본조회.md:41
            String mketIdCd,
            // ETF-specific fields (may be blank for non-ETF stocks)
            String etfTrgtNmixBstpCode, // underlying index code (e.g., "069500")
            String etfChasErngRtDbnb, // leverage ratio (e.g., "2.00", "-1.00" for inverse)
            String etfNascTpCd, // ETF type code for inverse/hedged detection
            // 상장폐지·거래정지 판정 필드 (REQ-WLSYNC-142, api-specs/kis/17-주식기본조회.md
            // "상장폐지·거래정지 판정 실측" 섹션). 상폐 종목도 rt_cd=0 정상 응답으로 온다 — rt_cd로 판정 금지.
            String lstgAbolDt, // 상장폐지일자(YYYYMMDD). 채워져 있으면 상폐, 공백/전부 0("00000000")이면 상폐 아님
            String trStopYn) {} // 거래정지여부(Y/N). lstgAbolDt가 빈 값이고 이 값이 Y면 거래정지(가역)
}
