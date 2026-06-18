package com.aaa.collector.stock.fundamental;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내주식종목투자의견 API 응답 (TR FHKST663300C0).
 *
 * <p>{@code output}(일자별 Object Array)만 사용한다. 매핑 대상 12개 필드만 선언하고 나머지는
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)}로 무시한다(매핑 컬럼은 {@code stock_id} FK 포함 13개,
 * AC-OPN-2). 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다.
 *
 * <p>{@link com.aaa.collector.stock.supply.KisInvestorTrendResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisInvestOpinionResponse(
        String rtCd, String msgCd, String msg1, List<InvestOpinionRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisInvestOpinionResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 투자의견 행 (output 배열 1건, §6.3 매핑표).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvestOpinionRow(
            /** 주식영업일자 (YYYYMMDD) */
            String stckBsopDate,
            /** 투자의견 */
            String invtOpnn,
            /** 투자의견구분코드 */
            String invtOpnnClsCode,
            /** 직전투자의견 */
            String rgbfInvtOpnn,
            /** 직전투자의견구분코드 */
            String rgbfInvtOpnnClsCode,
            /** 회원사명 (빈 문자열 가능 → DEFAULT '' 수용) */
            String mbcrName,
            /** HTS목표가격 (원, BIGINT) */
            String htsGoalPrc,
            /** 주식전일종가 (원, BIGINT) */
            String stckPrdyClpr,
            /** 주식N일괴리도 */
            String stckNdayEsdg,
            /** N일괴리율 */
            String ndayDprt,
            /** 주식선물괴리도 */
            String stftEsdg,
            /** 괴리율 */
            String dprt) {}
}
