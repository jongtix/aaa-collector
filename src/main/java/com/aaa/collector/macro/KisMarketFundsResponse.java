package com.aaa.collector.macro;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 증시자금종합 API 응답 (TR FHKST649100C0).
 *
 * <p>{@code output}(일자별 Object Array)을 사용한다. 일자별 한 행에 9개 금액 지표가 포함된다. {@link
 * com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisMarketFundsResponse(
        String rtCd, String msgCd, String msg1, List<MarketFundsRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisMarketFundsResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 증시자금 일자별 행 (output 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 9개 금액
     * 지표 전부 매핑(REQ-BATCH3-041). 단위 "억원" → 서비스 레이어에서 ×10^8 원 정규화(REQ-BATCH3-042).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketFundsRow(
            /** 영업 일자 (yyyyMMdd) */
            String bsopDate,
            /** 고객예탁금금액 (억원) */
            String custDpmnAmt,
            /** 신용융자잔고 (억원) */
            String crdtLoanRmnd,
            /** MMF금액 (억원) */
            String mmfAmt,
            /** 미수금액 (억원) */
            String unclAmt,
            /** 선물예수금금액 (억원) */
            String futsTfamAmt,
            /** 주식형금액 (억원) */
            String sttpAmt,
            /** 혼합형금액 (억원) */
            String mxtpAmt,
            /** 채권형금액 (억원) */
            String bntpAmt,
            /** 담보대출잔고금액 (억원) */
            String secuLendAmt) {}
}
