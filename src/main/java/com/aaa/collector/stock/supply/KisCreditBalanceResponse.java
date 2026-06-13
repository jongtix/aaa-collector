package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내주식 신용잔고 일별추이 API 응답 (TR FHPST04760000).
 *
 * <p>[HARD] 본 응답은 {@code output}(<b>단수</b>) 키를 일자별 Object Array로 사용한다 (투자자/공매도의 {@code output2}와
 * 다름, REQ-BATCH2-050).
 *
 * <p>날짜 매핑 주의(REQ-BATCH2-052): 저장 키 {@code trade_date}에는 {@code deal_date}(매매일자)를 매핑한다 — {@code
 * stlm_date}(결제일자)가 아님. 두 필드를 모두 보존하되 매핑 책임은 수집 서비스에 둔다.
 *
 * <p>{@link com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisCreditBalanceResponse(
        String rtCd, String msgCd, String msg1, List<CreditBalanceRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisCreditBalanceResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 신용잔고 일별추이 행 (output 배열 1건).
     *
     * <p>매핑 대상(REQ-BATCH2-051): 융자 8필드 + 대주 8필드. 금액은 만원 단위 무변환. 비율은 DECIMAL(7,4) 매핑.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditBalanceRow(
            /** 매매 일자 (yyyyMMdd) — trade_date로 매핑 */
            String dealDate,
            /** 결제 일자 (yyyyMMdd) — trade_date로 매핑하지 않음 */
            String stlmDate,
            /** 전체 융자 신규 주수 */
            String wholLoanNewStcn,
            /** 전체 융자 상환 주수 */
            String wholLoanRdmpStcn,
            /** 전체 융자 잔고 주수 */
            String wholLoanRmndStcn,
            /** 전체 융자 신규 금액 (만원 무변환) */
            String wholLoanNewAmt,
            /** 전체 융자 상환 금액 (만원 무변환) */
            String wholLoanRdmpAmt,
            /** 전체 융자 잔고 금액 (만원 무변환) */
            String wholLoanRmndAmt,
            /** 전체 융자 잔고 비율 (%) */
            String wholLoanRmndRate,
            /** 전체 융자 공여율 (%) */
            String wholLoanGvrt,
            /** 전체 대주 신규 주수 */
            String wholStlnNewStcn,
            /** 전체 대주 상환 주수 */
            String wholStlnRdmpStcn,
            /** 전체 대주 잔고 주수 */
            String wholStlnRmndStcn,
            /** 전체 대주 신규 금액 (만원 무변환) */
            String wholStlnNewAmt,
            /** 전체 대주 상환 금액 (만원 무변환) */
            String wholStlnRdmpAmt,
            /** 전체 대주 잔고 금액 (만원 무변환) */
            String wholStlnRmndAmt,
            /** 전체 대주 잔고 비율 (%) */
            String wholStlnRmndRate,
            /** 전체 대주 공여율 (%) */
            String wholStlnGvrt) {}
}
