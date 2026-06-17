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

@DisplayName("KisRevSplitResponse 역직렬화 단위 테스트")
class KisRevSplitResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output1 액면교체 행 역직렬화")
    class Output1Deserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[]}
                    """;

            KisRevSplitResponse response = objectMapper.readValue(json, KisRevSplitResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 액면교체 행 역직렬화 — 7필드 snake_case→camelCase 매핑 확인")
        void deserialize_output1Row_allFieldsMapped() throws Exception {
            // Arrange
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                      {
                        "record_date":"20260613",
                        "sht_cd":"096960",
                        "isin_name":"SK바이오사이언스",
                        "inter_bf_face_amt":"000000500",
                        "inter_af_face_amt":"000000100",
                        "td_stop_dt":"2026/06/12 ~",
                        "list_dt":"20260613"
                      }
                    ]}
                    """;

            // Act
            KisRevSplitResponse response = objectMapper.readValue(json, KisRevSplitResponse.class);

            // Assert
            assertThat(response.output1()).hasSize(1);
            KisRevSplitResponse.RevSplitRow row = response.output1().getFirst();
            assertThat(row.recordDate()).isEqualTo("20260613");
            assertThat(row.shtCd()).isEqualTo("096960");
            assertThat(row.isinName()).isEqualTo("SK바이오사이언스");
            assertThat(row.interBfFaceAmt()).isEqualTo("000000500");
            assertThat(row.interAfFaceAmt()).isEqualTo("000000100");
            assertThat(row.tdStopDt()).isEqualTo("2026/06/12 ~");
            assertThat(row.listDt()).isEqualTo("20260613");
        }

        @Test
        @DisplayName("9자리 zero-pad 면액 문자열 그대로 수신 — 파싱은 서비스 계층에서")
        void zeroPadAmounts_receivedAsString() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                      {
                        "record_date":"20260616",
                        "sht_cd":"025440",
                        "isin_name":"씨에스윈드",
                        "inter_bf_face_amt":"000000500",
                        "inter_af_face_amt":"000002500",
                        "td_stop_dt":"",
                        "list_dt":"20260616"
                      }
                    ]}
                    """;

            KisRevSplitResponse response = objectMapper.readValue(json, KisRevSplitResponse.class);

            assertThat(response.output1().getFirst().interBfFaceAmt()).isEqualTo("000000500");
            assertThat(response.output1().getFirst().interAfFaceAmt()).isEqualTo("000002500");
        }

        @Test
        @DisplayName("알 수 없는 필드 무시 (@JsonIgnoreProperties)")
        void unknownFields_ignored() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[],
                     "unknown_field":"ignored","another":"also_ignored"}
                    """;

            KisRevSplitResponse response = objectMapper.readValue(json, KisRevSplitResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output1 이 null 이면 빈 리스트로 대체된다")
        void nullOutput1_becomesEmptyList() {
            KisRevSplitResponse response = new KisRevSplitResponse("0", "MCA00000", "정상", null);

            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 은 불변 리스트다")
        void output1_isImmutable() {
            KisRevSplitResponse.RevSplitRow row =
                    new KisRevSplitResponse.RevSplitRow(
                            "20260613",
                            "096960",
                            "SK바이오사이언스",
                            "000000500",
                            "000000100",
                            "2026/06/12 ~",
                            "20260613");
            KisRevSplitResponse response =
                    new KisRevSplitResponse("0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output1().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
