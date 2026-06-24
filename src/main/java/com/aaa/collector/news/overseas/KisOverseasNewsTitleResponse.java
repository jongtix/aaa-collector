package com.aaa.collector.news.overseas;

import com.aaa.collector.kis.KisApiResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * KIS 해외뉴스종합(제목) API 응답 (TR HHPSTH60100C1, 명세 api-specs/kis/27).
 *
 * <p>T0 실측(2026-06-24, isa 단일키): {@code outblock1}은 배열(페이지당 10건), 시간 역순(최신→과거) 정렬. 페이징 커서는 {@code
 * CTS}가 아니라 직전 응답 마지막 행의 {@code data_dt}/{@code data_tm}이다(REQ-OVE-043). 응답 12필드는 명세 표와 일치하며, 종목 무관
 * 뉴스(거시·원자재)는 {@code symb}/{@code symb_name}/{@code exchange_cd}가 빈 문자열로 온다. {@link
 * com.aaa.collector.news.KisNewsTitleResponse} 패턴 답습.
 *
 * <p>전역 {@code spring.jackson.property-naming-strategy=SNAKE_CASE}로 JSON snake_case 키가 camelCase
 * 필드에 매핑된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KisOverseasNewsTitleResponse(
        String rtCd, String msgCd, String msg1, List<NewsRow> outblock1) implements KisApiResponse {

    /** 방어적 복사 — outblock1을 불변 리스트로 변환. */
    public KisOverseasNewsTitleResponse {
        outblock1 = outblock1 != null ? List.copyOf(outblock1) : List.of();
    }

    /**
     * 해외 뉴스 제목 행 (outblock1 배열 1건, 페이지당 10건).
     *
     * <p>필드명은 명세 27 snake_case 기준(전역 SNAKE_CASE 매핑). 12필드 전부 선언한다(T0 실측 일치). 종목 무관 뉴스는 {@code
     * symb}/{@code symbName}/{@code exchangeCd}가 빈 문자열로 오며 그대로 보존한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewsRow(
            /** 뉴스구분 (소문자 e 관찰) */
            String infoGb,
            /** 뉴스 고유키 → news_key (멱등 저장 유니크 키) */
            String newsKey,
            /** 조회일자 (yyyyMMdd) — 페이징 커서 구성 */
            String dataDt,
            /** 조회시간 (HHmmss) — 페이징 커서 구성 */
            String dataTm,
            /** 중분류 코드 */
            String classCd,
            /** 중분류명 */
            String className,
            /** 자료원 */
            String source,
            /** 국가코드 (US) */
            String nationCd,
            /** 거래소코드 (종목 무관 뉴스는 빈 문자열) */
            String exchangeCd,
            /** 종목코드 (종목 무관 뉴스는 빈 문자열) → symbol */
            String symb,
            /** 종목명 (종목 무관 뉴스는 빈 문자열) → symbol_name */
            String symbName,
            /** 제목 (본문 없음) */
            String title) {}
}
