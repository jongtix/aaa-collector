package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 종목별 투자자 매매동향 API 응답 (TR FHPTJ04160001).
 *
 * <p>{@code output2}(일자별 Object Array)만 사용한다. API는 투자자 11분류를 제공하나 본 배치는 외국인/기관계/개인 3분류만 매핑·저장한다
 * (REQ-BATCH2-032). 나머지 8분류 및 매도/매수 세부 필드는 {@code @JsonIgnoreProperties(ignoreUnknown=true)}로 무시한다.
 *
 * <p>{@link com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisInvestorTrendResponse(
        String rtCd, String msgCd, String msg1, List<InvestorTrendRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisInvestorTrendResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /**
     * 투자자 매매동향 행 (output2 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다.
     *
     * <p>매핑 대상(REQ-BATCH2-031): 외국인/기관계/개인 순매수 수량·거래대금 + 누적 거래량/거래대금. 8분류 필드는 선언하지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvestorTrendRow(
            /** 주식 영업 일자 (yyyyMMdd) */
            String stckBsopDate,
            /** 외국인 순매수 수량 (단위: 주, 음수 정상) */
            String frgnNtbyQty,
            /** 기관계 순매수 수량 (단위: 주, 음수 정상) */
            String orgnNtbyQty,
            /** 개인 순매수 수량 (단위: 주, 음수 정상) */
            String prsnNtbyQty,
            /** 외국인 순매수 거래 대금 (단위 OI-1 미확정, 기본 백만원 가정, 음수 정상) */
            String frgnNtbyTrPbmn,
            /** 기관계 순매수 거래 대금 (단위 OI-1 미확정, 기본 백만원 가정, 음수 정상) */
            String orgnNtbyTrPbmn,
            /** 개인 순매수 거래 대금 (단위 OI-1 미확정, 기본 백만원 가정, 음수 정상) */
            String prsnNtbyTrPbmn,
            /** 누적 거래량 (단위: 주) */
            String acmlVol,
            /** 누적 거래 대금 (단위: 백만원 — 원 변환 ×1,000,000) */
            String acmlTrPbmn) {}
}
