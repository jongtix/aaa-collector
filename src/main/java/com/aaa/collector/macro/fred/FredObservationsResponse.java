package com.aaa.collector.macro.fred;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * FRED Series Observations API 응답 DTO (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-031).
 *
 * <p>응답 구조: {@code {"count": N, "observations": [{"date": "YYYY-MM-DD", "value": "3.63"}, ...]}}
 *
 * <p>{@code value="."} 행은 데이터 미제공 — 수집 서비스가 skip 처리한다 (REQ-MACRO-EXT-032).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FredObservationsResponse(
        @JsonProperty("count") int count,
        @JsonProperty("observations") List<Observation> observations) {

    /** 방어적 복사 — observations 필드를 불변 리스트로 변환. */
    public FredObservationsResponse {
        observations = observations != null ? List.copyOf(observations) : List.of();
    }

    /** 개별 관측 행. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Observation(
            @JsonProperty("date") String date, @JsonProperty("value") String value) {}
}
