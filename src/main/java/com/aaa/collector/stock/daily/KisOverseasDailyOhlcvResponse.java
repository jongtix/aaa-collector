package com.aaa.collector.stock.daily;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 해외주식 기간별시세 API 응답 (TR HHDFS76240000, 명세22 {@code dailyprice}).
 *
 * <p>{@code output1}(종목 요약: zdiv/nrec)·{@code output2}(일별 시세 배열)를 사용한다. 적재 필드는 {@code output2}의
 * {@code xymd}/{@code open}/{@code high}/{@code low}/{@code clos}/{@code tvol}/{@code tamt} 7개이며,
 * 등락 정보({@code sign}/{@code diff}/{@code rate})·호가 스냅샷({@code pbid}/{@code vbid}/{@code
 * pask}/{@code vask})은 {@code @JsonIgnoreProperties(ignoreUnknown=true)}로 무시한다.
 *
 * <p>국내 {@code KisDailyOhlcvResponse}와 달리 {@code mod_yn}(변경여부) 필드가 없다 — 명세22에 해당 필드가 존재하지 않으므로 국내
 * 수정주가행 제외 단계는 해외에서 자연 누락된다(MI-03).
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE} 설정에 의해 snake_case 키가 camelCase
 * 필드에 매핑된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisOverseasDailyOhlcvResponse(
        String rtCd,
        String msgCd,
        String msg1,
        Output1 output1,
        List<OverseasDailyOhlcvRow> output2)
        implements KisApiResponse {

    /** 방어적 복사 — output2를 불변 리스트로 변환(빈응답 시 빈 리스트). */
    public KisOverseasDailyOhlcvResponse {
        output2 = output2 != null ? List.copyOf(output2) : List.of();
    }

    /** 종목 요약 (output1, single object). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output1(
            /** 실시간조회 종목코드 (예: DNASAAPL) — 적재 대상 아님 */
            String rsym,
            /** 가격 필드 소수점 자리수 — zdiv>4 가드(REQ-OVOH-016)에 사용 */
            String zdiv,
            /** output2 레코드 수 */
            String nrec) {}

    /**
     * 일별 시세 (output2 배열 1건).
     *
     * <p>모든 값은 String. 가격은 {@code output1.zdiv} 자리수의 소수(실측 4자리), 매핑 시 {@code BigDecimal}. {@code
     * tvol}/{@code tamt}는 정수 문자열(USD 단위), 매핑 시 {@code long}. 최신일 → 과거 내림차순 정렬.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OverseasDailyOhlcvRow(
            /** 영업일자 (yyyyMMdd) */
            String xymd,
            /** 종가 */
            String clos,
            /** 시가 */
            String open,
            /** 고가 */
            String high,
            /** 저가 */
            String low,
            /** 거래량 */
            String tvol,
            /** 거래대금 (USD) */
            String tamt) {}
}
