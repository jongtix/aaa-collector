package com.aaa.collector.macro.ecos;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * T1 RED — ECOS StatisticSearch 응답 DTO 역직렬화 테스트.
 *
 * <p>SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-011
 */
@DisplayName("EcosStatisticSearchResponse — 역직렬화 테스트")
class EcosStatisticSearchResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("정상 응답 역직렬화")
    class NormalResponse {

        @Test
        @DisplayName("단일 row 포함 응답 역직렬화 — list_total_count, TIME, DATA_VALUE 정확")
        void deserializeSingleRow() throws Exception {
            String json =
                    """
                    {
                      "StatisticSearch": {
                        "list_total_count": 1,
                        "row": [
                          {"TIME": "20260620", "DATA_VALUE": "3.50", "ITEM_CODE1": "0101000"}
                        ]
                      }
                    }
                    """;

            EcosStatisticSearchResponse response =
                    objectMapper.readValue(json, EcosStatisticSearchResponse.class);

            assertThat(response.statisticSearch()).isNotNull();
            assertThat(response.statisticSearch().listTotalCount()).isEqualTo(1);
            assertThat(response.statisticSearch().row()).hasSize(1);

            EcosStatisticSearchResponse.Row row = response.statisticSearch().row().getFirst();
            assertThat(row.time()).isEqualTo("20260620");
            assertThat(row.dataValue()).isEqualTo("3.50");
        }

        @Test
        @DisplayName("복수 row 포함 응답 역직렬화 — 모든 row 반환")
        void deserializeMultipleRows() throws Exception {
            String json =
                    """
                    {
                      "StatisticSearch": {
                        "list_total_count": 2,
                        "row": [
                          {"TIME": "20260619", "DATA_VALUE": "3.50"},
                          {"TIME": "20260620", "DATA_VALUE": "3.50"}
                        ]
                      }
                    }
                    """;

            EcosStatisticSearchResponse response =
                    objectMapper.readValue(json, EcosStatisticSearchResponse.class);

            assertThat(response.statisticSearch().row()).hasSize(2);
            assertThat(response.statisticSearch().row())
                    .extracting(EcosStatisticSearchResponse.Row::time)
                    .containsExactly("20260619", "20260620");
        }

        @Test
        @DisplayName("월별 TIME 형식(YYYYMM) 역직렬화 정상 처리")
        void deserializeMonthlyTimeFormat() throws Exception {
            String json =
                    """
                    {
                      "StatisticSearch": {
                        "list_total_count": 1,
                        "row": [
                          {"TIME": "202605", "DATA_VALUE": "2.3"}
                        ]
                      }
                    }
                    """;

            EcosStatisticSearchResponse response =
                    objectMapper.readValue(json, EcosStatisticSearchResponse.class);

            assertThat(response.statisticSearch().row().getFirst().time()).isEqualTo("202605");
        }

        @Test
        @DisplayName("분기별 TIME 형식(YYYYQN) 역직렬화 정상 처리")
        void deserializeQuarterlyTimeFormat() throws Exception {
            String json =
                    """
                    {
                      "StatisticSearch": {
                        "list_total_count": 1,
                        "row": [
                          {"TIME": "2026Q1", "DATA_VALUE": "1.5"}
                        ]
                      }
                    }
                    """;

            EcosStatisticSearchResponse response =
                    objectMapper.readValue(json, EcosStatisticSearchResponse.class);

            assertThat(response.statisticSearch().row().getFirst().time()).isEqualTo("2026Q1");
        }
    }

    @Nested
    @DisplayName("INFO-200 (0건) 응답 역직렬화")
    class Info200Response {

        @Test
        @DisplayName("RESULT.CODE=INFO-200 응답 — row 필드 없음, statisticSearch=null")
        void deserializeInfo200Response() throws Exception {
            String json =
                    """
                    {"RESULT": {"CODE": "INFO-200", "MESSAGE": "요청하신 데이터가 없습니다."}}
                    """;

            EcosStatisticSearchResponse response =
                    objectMapper.readValue(json, EcosStatisticSearchResponse.class);

            // INFO-200은 StatisticSearch 키가 없으므로 null
            assertThat(response.statisticSearch()).isNull();
        }
    }
}
