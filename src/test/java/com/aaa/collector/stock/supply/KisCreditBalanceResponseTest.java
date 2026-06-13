package com.aaa.collector.stock.supply;

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

@DisplayName("KisCreditBalanceResponse 역직렬화 단위 테스트")
class KisCreditBalanceResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output(단수 키) 일자별 행 역직렬화")
    class OutputDeserialization {

        @Test
        @DisplayName("신용잔고는 output(단수) 키를 사용하며 공통 필드 + 빈 배열 매핑")
        void deserialize_commonFields_singularOutputKey() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[]}
                    """;

            KisCreditBalanceResponse response =
                    objectMapper.readValue(json, KisCreditBalanceResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("융자/대주 매핑 대상 필드만 역직렬화하고 deal_date/stlm_date를 구분해 보존한다")
        void deserialize_mappedFieldsOnly_dealDateAndStlmDateDistinct() throws Exception {
            // Arrange
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                      {
                        "deal_date":"20260605",
                        "stlm_date":"20260609",
                        "whol_loan_new_stcn":"100",
                        "whol_loan_rdmp_stcn":"50",
                        "whol_loan_rmnd_stcn":"1000",
                        "whol_loan_new_amt":"700",
                        "whol_loan_rdmp_amt":"350",
                        "whol_loan_rmnd_amt":"7000",
                        "whol_loan_rmnd_rate":"1.5",
                        "whol_loan_gvrt":"2.5",
                        "whol_stln_new_stcn":"10",
                        "whol_stln_rdmp_stcn":"5",
                        "whol_stln_rmnd_stcn":"100",
                        "whol_stln_new_amt":"70",
                        "whol_stln_rdmp_amt":"35",
                        "whol_stln_rmnd_amt":"700",
                        "whol_stln_rmnd_rate":"0.5",
                        "whol_stln_gvrt":"0.3",
                        "stck_oprc":"74000"
                      }
                    ]}
                    """;

            // Act
            KisCreditBalanceResponse response =
                    objectMapper.readValue(json, KisCreditBalanceResponse.class);

            // Assert — extracting()으로 deal_date/stlm_date 구분 + 융자/대주 전 필드를 단일 assert로 검증
            assertThat(response.output()).hasSize(1);
            assertThat(response.output().getFirst())
                    .extracting(
                            KisCreditBalanceResponse.CreditBalanceRow::dealDate,
                            KisCreditBalanceResponse.CreditBalanceRow::stlmDate,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanNewStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanRdmpStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanRmndStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanNewAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanRdmpAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanRmndAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanRmndRate,
                            KisCreditBalanceResponse.CreditBalanceRow::wholLoanGvrt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnNewStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnRdmpStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnRmndStcn,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnNewAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnRdmpAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnRmndAmt,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnRmndRate,
                            KisCreditBalanceResponse.CreditBalanceRow::wholStlnGvrt)
                    .containsExactly(
                            "20260605",
                            "20260609",
                            "100",
                            "50",
                            "1000",
                            "700",
                            "350",
                            "7000",
                            "1.5",
                            "2.5",
                            "10",
                            "5",
                            "100",
                            "70",
                            "35",
                            "700",
                            "0.5",
                            "0.3");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output 이 null 이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisCreditBalanceResponse response =
                    new KisCreditBalanceResponse("0", "MCA00000", "정상", null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 은 불변 리스트다")
        void output_isImmutable() {
            KisCreditBalanceResponse.CreditBalanceRow row =
                    new KisCreditBalanceResponse.CreditBalanceRow(
                            "20260605",
                            "20260609",
                            "1",
                            "2",
                            "3",
                            "4",
                            "5",
                            "6",
                            "7",
                            "8",
                            "9",
                            "10",
                            "11",
                            "12",
                            "13",
                            "14",
                            "15",
                            "16");
            KisCreditBalanceResponse response =
                    new KisCreditBalanceResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
