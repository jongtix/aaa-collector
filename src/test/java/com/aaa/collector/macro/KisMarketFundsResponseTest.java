package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KisMarketFundsResponse 역직렬화 단위 테스트")
class KisMarketFundsResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output 일자별 행 역직렬화")
    class OutputDeserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[]}
                    """;

            KisMarketFundsResponse response =
                    objectMapper.readValue(json, KisMarketFundsResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 9개 금액 지표 행 역직렬화 — snake_case→camelCase 매핑 확인")
        void deserialize_outputRow_allNineFieldsMapped() throws Exception {
            // Arrange — 9개 금액 지표 전부 포함 (REQ-BATCH3-041)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                      {
                        "bsop_date":"20260612",
                        "cust_dpmn_amt":"535000",
                        "crdt_loan_rmnd":"195000",
                        "mmf_amt":"1850000",
                        "uncl_amt":"1200",
                        "futs_tfam_amt":"120000",
                        "sttp_amt":"90000",
                        "mxtp_amt":"30000",
                        "bntp_amt":"120000",
                        "secu_lend_amt":"250000"
                      }
                    ]}
                    """;

            // Act
            KisMarketFundsResponse response =
                    objectMapper.readValue(json, KisMarketFundsResponse.class);

            // Assert
            assertThat(response.output()).hasSize(1);
            assertThat(response.output().getFirst())
                    .extracting(
                            KisMarketFundsResponse.MarketFundsRow::bsopDate,
                            KisMarketFundsResponse.MarketFundsRow::custDpmnAmt,
                            KisMarketFundsResponse.MarketFundsRow::crdtLoanRmnd,
                            KisMarketFundsResponse.MarketFundsRow::mmfAmt,
                            KisMarketFundsResponse.MarketFundsRow::unclAmt,
                            KisMarketFundsResponse.MarketFundsRow::futsTfamAmt,
                            KisMarketFundsResponse.MarketFundsRow::sttpAmt,
                            KisMarketFundsResponse.MarketFundsRow::mxtpAmt,
                            KisMarketFundsResponse.MarketFundsRow::bntpAmt,
                            KisMarketFundsResponse.MarketFundsRow::secuLendAmt)
                    .containsExactly(
                            "20260612",
                            "535000",
                            "195000",
                            "1850000",
                            "1200",
                            "120000",
                            "90000",
                            "30000",
                            "120000",
                            "250000");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output 이 null 이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisMarketFundsResponse response =
                    new KisMarketFundsResponse("0", "MCA00000", "정상", null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 은 불변 리스트다")
        void output_isImmutable() {
            KisMarketFundsResponse.MarketFundsRow row =
                    new KisMarketFundsResponse.MarketFundsRow(
                            "20260612",
                            "535000",
                            "195000",
                            "1850000",
                            "1200",
                            "120000",
                            "90000",
                            "30000",
                            "120000",
                            "250000");
            KisMarketFundsResponse response =
                    new KisMarketFundsResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
