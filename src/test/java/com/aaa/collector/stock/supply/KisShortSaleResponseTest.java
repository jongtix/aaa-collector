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

@DisplayName("KisShortSaleResponse 역직렬화 단위 테스트")
class KisShortSaleResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output2 일자별 행 역직렬화")
    class Output2Deserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 + 빈 output2 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[]}
                    """;

            KisShortSaleResponse response =
                    objectMapper.readValue(json, KisShortSaleResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("공매도 매핑 대상 필드만 역직렬화하고 나머지는 무시한다")
        void deserialize_mappedFieldsOnly() throws Exception {
            // Arrange
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "stck_bsop_date":"20260605",
                        "ssts_cntg_qty":"12000",
                        "ssts_vol_rlim":"3.5",
                        "ssts_tr_pbmn":"900000000",
                        "ssts_tr_pbmn_rlim":"4.2",
                        "acml_ssts_cntg_qty":"50000",
                        "acml_ssts_cntg_qty_rlim":"5.1",
                        "acml_ssts_tr_pbmn":"3750000000",
                        "acml_ssts_tr_pbmn_rlim":"6.3",
                        "stck_oprc":"74000",
                        "avrg_prc":"75000"
                      }
                    ]}
                    """;

            // Act
            KisShortSaleResponse response =
                    objectMapper.readValue(json, KisShortSaleResponse.class);

            // Assert — extracting()으로 매핑 필드 전건을 단일 assert로 검증
            assertThat(response.output2()).hasSize(1);
            assertThat(response.output2().getFirst())
                    .extracting(
                            KisShortSaleResponse.ShortSaleRow::stckBsopDate,
                            KisShortSaleResponse.ShortSaleRow::sstsCntgQty,
                            KisShortSaleResponse.ShortSaleRow::sstsVolRlim,
                            KisShortSaleResponse.ShortSaleRow::sstsTrPbmn,
                            KisShortSaleResponse.ShortSaleRow::sstsTrPbmnRlim,
                            KisShortSaleResponse.ShortSaleRow::acmlSstsCntgQty,
                            KisShortSaleResponse.ShortSaleRow::acmlSstsCntgQtyRlim,
                            KisShortSaleResponse.ShortSaleRow::acmlSstsTrPbmn,
                            KisShortSaleResponse.ShortSaleRow::acmlSstsTrPbmnRlim)
                    .containsExactly(
                            "20260605",
                            "12000",
                            "3.5",
                            "900000000",
                            "4.2",
                            "50000",
                            "5.1",
                            "3750000000",
                            "6.3");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output2 가 null 이면 빈 리스트로 대체된다")
        void nullOutput2_becomesEmptyList() {
            KisShortSaleResponse response = new KisShortSaleResponse("0", "MCA00000", "정상", null);

            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("output2 는 불변 리스트다")
        void output2_isImmutable() {
            KisShortSaleResponse.ShortSaleRow row =
                    new KisShortSaleResponse.ShortSaleRow(
                            "20260605", "1", "2", "3", "4", "5", "6", "7", "8");
            KisShortSaleResponse response =
                    new KisShortSaleResponse("0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output2().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
