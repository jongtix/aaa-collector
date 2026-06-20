package com.aaa.collector.macro.ecos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * ECOS StatisticSearch API 응답 DTO (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-011).
 *
 * <p>정상 응답: {@code {"StatisticSearch": {"list_total_count": N, "row": [...]}}}
 *
 * <p>INFO-200 (0건) 응답: {@code {"RESULT": {"CODE": "INFO-200", ...}}} — {@code statisticSearch} 필드가
 * 없으므로 {@code null}로 역직렬화된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EcosStatisticSearchResponse(
        @JsonProperty("StatisticSearch") StatisticSearch statisticSearch) {

    /** StatisticSearch 내부 객체. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatisticSearch(
            @JsonProperty("list_total_count") int listTotalCount,
            @JsonProperty("row") List<Row> row) {}

    /** 개별 관측 행. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            @JsonProperty("TIME") String time, @JsonProperty("DATA_VALUE") String dataValue) {}
}
