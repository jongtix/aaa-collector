package com.aaa.collector.stock;

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

@DisplayName("KisDividendScheduleResponse 역직렬화 단위 테스트")
class KisDividendScheduleResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output1 배당 일정 행 역직렬화")
    class Output1Deserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[]}
                    """;

            KisDividendScheduleResponse response =
                    objectMapper.readValue(json, KisDividendScheduleResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 배당 일정 행 역직렬화 — 모든 필드 snake_case→camelCase 매핑 확인")
        void deserialize_output1Row_allFieldsMapped() throws Exception {
            // Arrange
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                      {
                        "sht_cd":"005930",
                        "record_date":"20260612",
                        "divi_kind":"결산배당",
                        "per_sto_divi_amt":"361",
                        "divi_rate":"0.50",
                        "stk_divi_rate":"0.00",
                        "divi_pay_dt":"20260814",
                        "stk_div_pay_dt":"",
                        "odd_pay_dt":"",
                        "face_val":"100",
                        "stk_kind":"보통주",
                        "high_divi_gb":"N"
                      }
                    ]}
                    """;

            // Act
            KisDividendScheduleResponse response =
                    objectMapper.readValue(json, KisDividendScheduleResponse.class);

            // Assert
            assertThat(response.output1()).hasSize(1);
            assertThat(response.output1().getFirst())
                    .extracting(
                            KisDividendScheduleResponse.DividendRow::shtCd,
                            KisDividendScheduleResponse.DividendRow::recordDate,
                            KisDividendScheduleResponse.DividendRow::diviKind,
                            KisDividendScheduleResponse.DividendRow::perStoDiviAmt,
                            KisDividendScheduleResponse.DividendRow::diviRate,
                            KisDividendScheduleResponse.DividendRow::stkDiviRate,
                            KisDividendScheduleResponse.DividendRow::diviPayDt,
                            KisDividendScheduleResponse.DividendRow::stkDivPayDt,
                            KisDividendScheduleResponse.DividendRow::oddPayDt,
                            KisDividendScheduleResponse.DividendRow::faceVal,
                            KisDividendScheduleResponse.DividendRow::stkKind,
                            KisDividendScheduleResponse.DividendRow::highDiviGb)
                    .containsExactly(
                            "005930",
                            "20260612",
                            "결산배당",
                            "361",
                            "0.50",
                            "0.00",
                            "20260814",
                            "",
                            "",
                            "100",
                            "보통주",
                            "N");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output1 이 null 이면 빈 리스트로 대체된다")
        void nullOutput1_becomesEmptyList() {
            KisDividendScheduleResponse response =
                    new KisDividendScheduleResponse("0", "MCA00000", "정상", null);

            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 은 불변 리스트다")
        void output1_isImmutable() {
            KisDividendScheduleResponse.DividendRow row =
                    new KisDividendScheduleResponse.DividendRow(
                            "005930",
                            "20260612",
                            "결산배당",
                            "361",
                            "0.50",
                            "0.00",
                            "20260814",
                            "",
                            "",
                            "100",
                            "보통주",
                            "N");
            KisDividendScheduleResponse response =
                    new KisDividendScheduleResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output1().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
