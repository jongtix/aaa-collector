package com.aaa.collector.macro.ecos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * ECOS StatisticSearch API 응답 DTO (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-011,
 * SPEC-COLLECTOR-ECOS-DATEFMT-001 REQ-ECOSFMT-007).
 *
 * <p>정상 응답: {@code {"StatisticSearch": {"list_total_count": N, "row": [...]}}}
 *
 * <p>INFO-200 (0건) / ERROR-* (오류) 응답: {@code {"RESULT": {"CODE": ..., "MESSAGE": ...}}} — {@code
 * statisticSearch} 필드가 없으므로 {@code null}로 역직렬화되고, {@code result}로 {@code CODE}/{@code MESSAGE}가
 * 노출된다(REQ-ECOSFMT-007 — RESULT를 ignoreUnknown으로 폐기하지 않는다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EcosStatisticSearchResponse(
        @JsonProperty("StatisticSearch") StatisticSearch statisticSearch,
        @JsonProperty("RESULT") Result result) {

    /** StatisticSearch 내부 객체. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatisticSearch(
            @JsonProperty("list_total_count") int listTotalCount,
            @JsonProperty("row") List<Row> row) {

        /** 방어적 복사 — row 필드를 불변 리스트로 변환. */
        public StatisticSearch {
            row = row != null ? List.copyOf(row) : List.of();
        }
    }

    /** 개별 관측 행. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            @JsonProperty("TIME") String time, @JsonProperty("DATA_VALUE") String dataValue) {}

    /** 최상위 RESULT 객체 — INFO-200(정상 0건) 또는 ERROR-*(오류) 코드/메시지. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("CODE") String code, @JsonProperty("MESSAGE") String message) {}
}
