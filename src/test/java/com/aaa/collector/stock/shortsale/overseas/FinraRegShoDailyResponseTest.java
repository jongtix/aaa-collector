package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FinraRegShoDailyResponse 역직렬화")
class FinraRegShoDailyResponseTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Nested
    @DisplayName("regShoDaily 실측 응답(명세 00)")
    class RealFixture {

        // 명세 00 §"실제 호출 예시" AAPL tradeReportDate=2026-01-06 시설별 3행 실측 JSON
        private static final String AAPL_THREE_FACILITY_ROWS =
                """
                [
                  {"reportingFacilityCode":"NCTRF","marketCode":"B","tradeReportDate":"2026-01-06","securitiesInformationProcessorSymbolIdentifier":"AAPL","shortParQuantity":69397,"shortExemptParQuantity":20,"totalParQuantity":154130},
                  {"reportingFacilityCode":"NQTRF","marketCode":"Q","tradeReportDate":"2026-01-06","securitiesInformationProcessorSymbolIdentifier":"AAPL","shortParQuantity":4891733,"shortExemptParQuantity":85087,"totalParQuantity":17699980},
                  {"reportingFacilityCode":"NYTRF","marketCode":"N","tradeReportDate":"2026-01-06","securitiesInformationProcessorSymbolIdentifier":"AAPL","shortParQuantity":198716,"shortExemptParQuantity":250,"totalParQuantity":588753}
                ]
                """;

        @Test
        @DisplayName("시설별 3행을 record 리스트로 역직렬화한다")
        void deserializesThreeFacilityRows() throws Exception {
            // Act
            List<FinraRegShoDailyResponse> rows =
                    objectMapper.readValue(
                            AAPL_THREE_FACILITY_ROWS, new FinraRegShoDailyResponseListType());

            // Assert
            assertThat(rows).hasSize(3);
            FinraRegShoDailyResponse first = rows.get(0);
            assertThat(first.tradeReportDate()).isEqualTo(LocalDate.of(2026, 1, 6));
            assertThat(first.symbol()).isEqualTo("AAPL");
            assertThat(first.shortParQuantity()).isEqualByComparingTo("69397");
            assertThat(first.totalParQuantity()).isEqualByComparingTo("154130");
        }

        @Test
        @DisplayName("미사용 필드(reportingFacilityCode/marketCode/shortExemptParQuantity)는 무시한다")
        void ignoresUnknownFields() throws Exception {
            // 명세에 없는 미래 필드를 섞어도 @JsonIgnoreProperties(ignoreUnknown=true)로 무시됨
            String withExtraField =
                    """
                    [
                      {"reportingFacilityCode":"NQTRF","marketCode":"Q","tradeReportDate":"2026-01-06","securitiesInformationProcessorSymbolIdentifier":"AAPL","shortParQuantity":4891733,"shortExemptParQuantity":85087,"totalParQuantity":17699980,"unexpectedFutureField":"x"}
                    ]
                    """;

            // Act & Assert
            List<FinraRegShoDailyResponse> rows =
                    objectMapper.readValue(withExtraField, new FinraRegShoDailyResponseListType());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).shortParQuantity()).isEqualByComparingTo("4891733");
        }
    }

    /** {@code List<FinraRegShoDailyResponse>}용 Jackson TypeReference. */
    private static final class FinraRegShoDailyResponseListType
            extends com.fasterxml.jackson.core.type.TypeReference<List<FinraRegShoDailyResponse>> {}
}
