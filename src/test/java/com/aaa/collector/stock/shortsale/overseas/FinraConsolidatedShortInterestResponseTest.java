package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FinraConsolidatedShortInterestResponse 역직렬화")
class FinraConsolidatedShortInterestResponseTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Nested
    @DisplayName("consolidatedShortInterest 실측 응답(명세 01)")
    class RealFixture {

        // 명세 01 §"실제 호출 예시" AAPL settlementDate=2026-04-15 실측 JSON (배열 형태로 반환)
        private static final String AAPL_SETTLEMENT_ROW =
                """
                [
                  {
                    "symbolCode": "AAPL",
                    "issueName": "Apple Inc. Common Stock",
                    "settlementDate": "2026-04-15",
                    "accountingYearMonthNumber": 20260415,
                    "marketClassCode": "NNM",
                    "issuerServicesGroupExchangeCode": "R",
                    "currentShortPositionQuantity": 134422787,
                    "previousShortPositionQuantity": 126771284,
                    "changePreviousNumber": 7651503,
                    "changePercent": 6.04,
                    "averageDailyVolumeQuantity": 39674165,
                    "daysToCoverQuantity": 3.39,
                    "stockSplitFlag": null,
                    "revisionFlag": null
                  }
                ]
                """;

        @Test
        @DisplayName("잔고 행을 record로 역직렬화한다")
        void deserializesSettlementRow() throws Exception {
            // Act
            List<FinraConsolidatedShortInterestResponse> rows =
                    objectMapper.readValue(
                            AAPL_SETTLEMENT_ROW,
                            new FinraConsolidatedShortInterestResponseListType());

            // Assert
            assertThat(rows).hasSize(1);
            FinraConsolidatedShortInterestResponse row = rows.get(0);
            assertThat(row.symbolCode()).isEqualTo("AAPL");
            assertThat(row.settlementDate()).isEqualTo(LocalDate.of(2026, 4, 15));
            assertThat(row.currentShortPositionQuantity()).isEqualByComparingTo("134422787");
            assertThat(row.revisionFlag()).isNull();
        }

        @Test
        @DisplayName("revisionFlag=\"R\"(잔고 수정) 값을 보존한다")
        void preservesRevisionFlag() throws Exception {
            String revisedRow =
                    """
                    [
                      {"symbolCode":"AAPL","settlementDate":"2026-04-15","currentShortPositionQuantity":140000000,"revisionFlag":"R"}
                    ]
                    """;

            // Act & Assert
            List<FinraConsolidatedShortInterestResponse> rows =
                    objectMapper.readValue(
                            revisedRow, new FinraConsolidatedShortInterestResponseListType());
            assertThat(rows.get(0).revisionFlag()).isEqualTo("R");
        }

        @Test
        @DisplayName("미사용 필드(issueName/daysToCoverQuantity 등)는 무시한다")
        void ignoresUnknownFields() throws Exception {
            // currentShortPositionQuantity/settlementDate/symbolCode/revisionFlag 외 필드는 무시
            List<FinraConsolidatedShortInterestResponse> rows =
                    objectMapper.readValue(
                            AAPL_SETTLEMENT_ROW,
                            new FinraConsolidatedShortInterestResponseListType());
            assertThat(rows.get(0).symbolCode()).isEqualTo("AAPL");
        }
    }

    /** {@code List<FinraConsolidatedShortInterestResponse>}용 Jackson TypeReference. */
    private static final class FinraConsolidatedShortInterestResponseListType
            extends com.fasterxml.jackson.core.type.TypeReference<
                    List<FinraConsolidatedShortInterestResponse>> {}
}
