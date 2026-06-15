package com.aaa.collector.news;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 종합시황공시제목 API 응답 (TR FHKST01011800).
 *
 * <p>T0 실측(v0.5.0): {@code output}은 배열(페이지당 40건)이다. api-specs 07번의 "Object, single" 표기는 오류. {@code
 * cntt_usiq_srno} 내림차순(최신 우선) 정렬. iscd1~5만 매핑(iscd6~10·kor_isnm1~10 범위 밖). {@link
 * com.aaa.collector.stock.daily.KisDailyOhlcvResponse} 패턴 답습.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisNewsTitleResponse(
        String rtCd, String msgCd, String msg1, List<NewsTitleRow> output)
        implements KisApiResponse {

    /** 방어적 복사 — output 필드를 불변 리스트로 변환. */
    public KisNewsTitleResponse {
        output = output != null ? List.copyOf(output) : List.of();
    }

    /**
     * 뉴스 제목 행 (output 배열 1건, 페이지당 최대 40건).
     *
     * <p>필드명은 KIS API 명세 snake_case 기준. 전역 {@code PropertyNamingStrategies.SNAKE_CASE}로 매핑된다. 매핑 대상
     * 필드만 선언한다(REQ-BATCH3-061). iscd6~10·kor_isnm1~10은 선언하지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewsTitleRow(
            /** 내용 조회용 일련번호 (19자리, 내림차순 = 최신 우선) → serial_no */
            String cnttUsiqSrno,
            /** 뉴스 제공 업체 코드 → provider_code */
            String newsOferEntpCode,
            /** 작성 일자 (yyyyMMdd) */
            String dataDt,
            /** 작성 시각 (HHmmss) */
            String dataTm,
            /** HTS 공시 제목 내용 → title */
            String htsPbntTitlCntt,
            /** 뉴스 대구분 코드 → category_code */
            String newsLrdvCode,
            /** 자료원 → source */
            String dorg,
            /** 관련 종목코드 1 */
            String iscd1,
            /** 관련 종목코드 2 */
            String iscd2,
            /** 관련 종목코드 3 */
            String iscd3,
            /** 관련 종목코드 4 */
            String iscd4,
            /** 관련 종목코드 5 */
            String iscd5) {}
}
