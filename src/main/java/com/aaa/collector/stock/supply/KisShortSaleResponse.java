package com.aaa.collector.stock.supply;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내주식 공매도 일별추이 API 응답 (TR FHPST04830000).
 *
 * <p>{@code output2}(일자별 Object Array)만 사용한다. {@code FID_INPUT_DATE_1}/{@code FID_INPUT_DATE_2} 기간
 * 조회로 14일 윈도우를 1회 호출에 충족한다(REQ-BATCH2-040).
 *
 * <p>{@link com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisShortSaleResponse(
        String rtCd, String msgCd, String msg1, List<ShortSaleRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisShortSaleResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /**
     * 공매도 일별추이 행 (output2 배열 1건).
     *
     * <p>매핑 대상(REQ-BATCH2-041): 공매도 체결 수량/거래대금 + 비율 + 누적. 금액은 원 단위 무변환(OI-2). 비율은 DECIMAL(7,4) 매핑.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShortSaleRow(
            /** 주식 영업 일자 (yyyyMMdd) */
            String stckBsopDate,
            /** 공매도 체결 수량 */
            String sstsCntgQty,
            /** 공매도 거래량 비중 (%) */
            String sstsVolRlim,
            /** 공매도 거래 대금 (원 무변환) */
            String sstsTrPbmn,
            /** 공매도 거래대금 비중 (%) */
            String sstsTrPbmnRlim,
            /** 누적 공매도 체결 수량 */
            String acmlSstsCntgQty,
            /** 누적 공매도 체결 수량 비중 (%) */
            String acmlSstsCntgQtyRlim,
            /** 누적 공매도 거래 대금 (원 무변환) */
            String acmlSstsTrPbmn,
            /** 누적 공매도 거래 대금 비중 (%) */
            String acmlSstsTrPbmnRlim) {}
}
