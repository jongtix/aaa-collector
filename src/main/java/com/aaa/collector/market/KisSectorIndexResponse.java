package com.aaa.collector.market;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 업종지수 일봉 API 응답 (TR FHKUP03500100).
 *
 * <p>{@code output2}(일자별 Object Array)만 사용한다. output1은 당일 지수 요약이며 본 배치에서는 불필요하다. {@link
 * com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisSectorIndexResponse(
        String rtCd, String msgCd, String msg1, List<SectorIndexRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisSectorIndexResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /**
     * 업종지수 일봉 행 (output2 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 매핑 대상
     * 필드만 선언한다(REQ-BATCH3-022).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SectorIndexRow(
            /** 주식 영업 일자 (yyyyMMdd) */
            String stckBsopDate,
            /** 업종 지수 시가 */
            String bstpNmixOprc,
            /** 업종 지수 최고가 */
            String bstpNmixHgpr,
            /** 업종 지수 최저가 */
            String bstpNmixLwpr,
            /** 업종 지수 현재가 (종가) */
            String bstpNmixPrpr,
            /** 누적 거래량 */
            String acmlVol,
            /** 누적 거래 대금 */
            String acmlTrPbmn) {}
}
