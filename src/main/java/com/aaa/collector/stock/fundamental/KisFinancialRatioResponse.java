package com.aaa.collector.stock.fundamental;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내주식재무비율 API 응답 (TR FHKST66430300).
 *
 * <p>{@code output}(결산기별 Object Array)만 사용한다. {@code 06} 실측(2026-06-15) 기준 한 호출 ~23행을 반환하며, 반환된 모든
 * 행을 멱등 저장 대상으로 처리한다(REQ-BATCH4-024). 매핑 대상 10개 필드만 선언하고 나머지는
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)}로 무시한다. 전역 {@code
 * PropertyNamingStrategies.SNAKE_CASE}로 매핑된다.
 *
 * <p>{@link com.aaa.collector.stock.supply.KisInvestorTrendResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisFinancialRatioResponse(
        String rtCd, String msgCd, String msg1, List<FinancialRatioRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisFinancialRatioResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 재무비율 행 (output 배열 1건, §6.3 매핑표).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FinancialRatioRow(
            /** 결산년월 (YYYYMM, 6자 — {@code 06} 실측 확정) */
            String stacYymm,
            /** 매출액 증가율 (%) */
            String grs,
            /** 영업이익 증가율 (%, 적자지속/흑자전환/적자전환 시 0 표기) */
            String bsopPrfiInrt,
            /** 순이익 증가율 (%) */
            String ntinInrt,
            /** ROE 값 (%) */
            String roeVal,
            /** EPS (원, {@code 06} 실측 ".00" 소수 접미사 도착) */
            String eps,
            /** 주당매출액 SPS (원) */
            String sps,
            /** BPS (원, {@code 06} 실측 ".00" 소수 접미사 도착) */
            String bps,
            /** 유보비율 (%) */
            String rsrvRate,
            /** 부채비율 (%) */
            String lbltRate) {}
}
