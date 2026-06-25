package com.aaa.collector.dart.external;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenDART {@code list.json} 응답 DTO (SPEC-COLLECTOR-DART-001).
 *
 * <p>api-specs/dart/01-공시검색.md 실측 기준(2026-06-25). SNAKE_CASE ObjectMapper 적용 — 필드명이 snake_case 응답
 * 키와 자동 대응.
 */
public class DartListResponse {

    /** 에러 및 정보 코드 — "000" 이면 정상. */
    @JsonProperty("status")
    private String status;

    /** 에러 메시지. */
    @JsonProperty("message")
    private String message;

    /** 총 건수. */
    @JsonProperty("total_count")
    private Integer totalCount;

    /** 페이지당 건수. */
    @JsonProperty("page_count")
    private Integer pageCount;

    /** 현재 페이지 번호. */
    @JsonProperty("page_no")
    private Integer pageNo;

    /** 총 페이지 수. */
    @JsonProperty("total_page")
    private Integer totalPage;

    /** 공시 목록. */
    @JsonProperty("list")
    private List<DisclosureItem> list;

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public Integer getTotalPage() {
        return totalPage;
    }

    public List<DisclosureItem> getList() {
        return list != null ? list : List.of();
    }

    /** 공시 목록 단건 항목. */
    public static class DisclosureItem {

        /** DART 고유번호 8자리. */
        @JsonProperty("corp_code")
        private String corpCode;

        /** 법인구분 Y/K/N/E. */
        @JsonProperty("corp_cls")
        private String corpCls;

        /** 종목코드 6자리 — 비상장사는 빈 문자열. */
        @JsonProperty("stock_code")
        private String stockCode;

        /** 보고서명. */
        @JsonProperty("report_nm")
        private String reportNm;

        /** 접수번호 14자리. */
        @JsonProperty("rcept_no")
        private String rceptNo;

        /** 공시 제출인명. */
        @JsonProperty("flr_nm")
        private String flrNm;

        /** 접수일자 YYYYMMDD. */
        @JsonProperty("rcept_dt")
        private String rceptDt;

        /** 비고. */
        @JsonProperty("rm")
        private String rm;

        public String getCorpCode() {
            return corpCode;
        }

        public String getCorpCls() {
            return corpCls;
        }

        public String getStockCode() {
            return stockCode;
        }

        public String getReportNm() {
            return reportNm;
        }

        public String getRceptNo() {
            return rceptNo;
        }

        public String getFlrNm() {
            return flrNm;
        }

        public String getRceptDt() {
            return rceptDt;
        }

        public String getRm() {
            return rm;
        }

        /**
         * 테스트 픽스처 팩토리 메서드.
         *
         * <p>단위 테스트에서 목(mock) 항목을 생성할 때 사용한다 — 리플렉션 우회. 다수 String 파라미터는 JSON 응답 필드와 1:1 대응하므로 별도
         * 컨테이너 객체를 도입하지 않는다.
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
            DisclosureItem item = new DisclosureItem();
            item.corpCode = corpCode;
            item.corpCls = corpCls;
            item.stockCode = stockCode;
            item.reportNm = reportNm;
            item.rceptNo = rceptNo;
            item.flrNm = flrNm;
            item.rceptDt = rceptDt;
            item.rm = rm;
            return item;
        }
    }
}
