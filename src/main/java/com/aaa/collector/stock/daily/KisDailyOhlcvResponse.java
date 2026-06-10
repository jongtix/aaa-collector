package com.aaa.collector.stock.daily;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 국내주식기간별시세 API 응답 (TR FHKST03010100).
 *
 * <p>output2(배열) 필드만 사용한다 — 일봉 리스트. output1은 당일 현재가 요약이며 본 배치에서는 불필요하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisDailyOhlcvResponse(
        String rtCd, String msgCd, String msg1, List<DailyOhlcvRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2 필드를 불변 리스트로 변환. */
    public KisDailyOhlcvResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /**
     * 일봉 행 (output2 배열 1건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. Spring 전역 SNAKE_CASE 설정이 없는 단위 테스트 환경에서도 정상 역직렬화되도록 전역
     * ObjectMapper의 {@code PropertyNamingStrategies.SNAKE_CASE} 설정을 테스트에서도 동일하게 적용해야 한다.
     *
     * <p>검증 규칙(REQ-BATCH-033):
     *
     * <ul>
     *   <li>null / 빈 문자열 필드는 유효하지 않다.
     *   <li>가격(close/open/high/low) ≤ 0인 행은 저장 제외.
     *   <li>거래량(acml_vol) ≤ 0인 행은 저장 제외.
     *   <li>mod_yn = "Y"인 행은 저장 제외 (체결 미발생 — 시가 없는 날).
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyOhlcvRow(
            /** 주식 영업 일자 (yyyyMMdd) */
            String stckBsopDate,
            /** 주식 종가 */
            String stckClpr,
            /** 주식 시가 */
            String stckOprc,
            /** 주식 최고가 */
            String stckHgpr,
            /** 주식 최저가 */
            String stckLwpr,
            /** 누적 거래량 */
            String acmlVol,
            /** 누적 거래 대금 */
            String acmlTrPbmn,
            /** 변경 여부 — Y: 체결 미발생(시가 없는 날) */
            String modYn) {}
}
