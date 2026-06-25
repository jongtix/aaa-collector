package com.aaa.collector.dart.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenDART {@code list.json} 응답 DTO (SPEC-COLLECTOR-DART-001).
 *
 * <p>api-specs/dart/01-공시검색.md 실측 기준(2026-06-25).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DartListResponse(
        @JsonProperty("status") String status,
        @JsonProperty("message") String message,
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("page_count") Integer pageCount,
        @JsonProperty("page_no") Integer pageNo,
        @JsonProperty("total_page") Integer totalPage,
        @JsonProperty("list") List<DisclosureItem> list) {

    /** 방어적 복사 — list 필드를 불변 리스트로 변환. */
    public DartListResponse {
        list = list != null ? List.copyOf(list) : List.of();
    }

    /** list 필드의 null-safe getter. */
    public List<DisclosureItem> getList() {
        return list != null ? list : List.of();
    }

    /** 공시 목록 단건 항목. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisclosureItem(
            @JsonProperty("corp_code") String corpCode,
            @JsonProperty("corp_cls") String corpCls,
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("report_nm") String reportNm,
            @JsonProperty("rcept_no") String rceptNo,
            @JsonProperty("flr_nm") String flrNm,
            @JsonProperty("rcept_dt") String rceptDt,
            @JsonProperty("rm") String rm) {

        /**
         * 테스트 픽스처 팩토리 메서드.
         *
         * <p>단위 테스트에서 목(mock) 항목을 생성할 때 사용한다.
         */
        @SuppressWarnings("PMD.UseObjectForClearerAPI") // JSON 응답 필드와 1:1 대응 — 컨테이너 불필요
        public static DisclosureItem of(
                String corpCode,
                String corpCls,
                String stockCode,
                String reportNm,
                String rceptNo,
                String flrNm,
                String rceptDt,
                String rm) {
            return new DisclosureItem(
                    corpCode, corpCls, stockCode, reportNm, rceptNo, flrNm, rceptDt, rm);
        }
    }
}
