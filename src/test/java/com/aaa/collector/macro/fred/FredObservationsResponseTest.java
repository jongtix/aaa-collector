package com.aaa.collector.macro.fred;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** T3 RED — FRED Observations 응답 DTO 역직렬화 테스트 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-031). */
@DisplayName("FredObservationsResponse — 역직렬화 테스트")
class FredObservationsResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("정상 응답 역직렬화")
    class NormalResponse {

        @Test
        @DisplayName("복수 observation 포함 응답 역직렬화 — count, date, value 정확")
        void deserializeMultipleObservations() throws Exception {
            String json =
                    """
                    {
                      "count": 2,
                      "observations": [
                        {"date": "2026-06-17", "value": "3.63"},
                        {"date": "2026-06-16", "value": "."}
                      ]
                    }
                    """;

            FredObservationsResponse response =
                    objectMapper.readValue(json, FredObservationsResponse.class);

            assertThat(response.count()).isEqualTo(2);
            assertThat(response.observations()).hasSize(2);

            FredObservationsResponse.Observation first = response.observations().get(0);
            assertThat(first.date()).isEqualTo("2026-06-17");
            assertThat(first.value()).isEqualTo("3.63");

            FredObservationsResponse.Observation second = response.observations().get(1);
            assertThat(second.date()).isEqualTo("2026-06-16");
            assertThat(second.value()).isEqualTo(".");
        }

        @Test
        @DisplayName("value='.' 행 — '.' 그대로 역직렬화 (skip 로직은 서비스 담당)")
        void deserializeDotValue_returnedAsIs() throws Exception {
            String json =
                    """
                    {
                      "count": 1,
                      "observations": [
                        {"date": "2026-06-14", "value": "."}
                      ]
                    }
                    """;

            FredObservationsResponse response =
                    objectMapper.readValue(json, FredObservationsResponse.class);

            assertThat(response.observations().get(0).value()).isEqualTo(".");
        }

        @Test
        @DisplayName("빈 observations 배열 역직렬화")
        void deserializeEmptyObservations() throws Exception {
            String json =
                    """
                    {"count": 0, "observations": []}
                    """;

            FredObservationsResponse response =
                    objectMapper.readValue(json, FredObservationsResponse.class);

            assertThat(response.count()).isZero();
            assertThat(response.observations()).isEmpty();
        }

        @Test
        @DisplayName("알 수 없는 필드(realtime_start 등) 무시")
        void deserializeIgnoresUnknownFields() throws Exception {
            String json =
                    """
                    {
                      "realtime_start": "1776-07-04",
                      "count": 1,
                      "observations": [
                        {"realtime_start": "2026-06-17", "date": "2026-06-17", "value": "3.63"}
                      ]
                    }
                    """;

            FredObservationsResponse response =
                    objectMapper.readValue(json, FredObservationsResponse.class);

            assertThat(response.count()).isEqualTo(1);
            assertThat(response.observations().get(0).date()).isEqualTo("2026-06-17");
        }
    }
}
