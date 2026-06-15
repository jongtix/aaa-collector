package com.aaa.collector.market;

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

@DisplayName("KisSectorIndexResponse 역직렬화 단위 테스트")
class KisSectorIndexResponseTest {

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
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[]}
                    """;

            KisSectorIndexResponse response =
                    objectMapper.readValue(json, KisSectorIndexResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("output2 업종지수 OHLCV 행 역직렬화 — snake_case→camelCase 매핑 확인")
        void deserialize_output2Row_allFieldsMapped() throws Exception {
            // Arrange
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "stck_bsop_date":"20260612",
                        "bstp_nmix_oprc":"8100.50",
                        "bstp_nmix_hgpr":"8200.30",
                        "bstp_nmix_lwpr":"8050.10",
                        "bstp_nmix_prpr":"8123.62",
                        "acml_vol":"350000000",
                        "acml_tr_pbmn":"5500000000000"
                      }
                    ]}
                    """;

            // Act
            KisSectorIndexResponse response =
                    objectMapper.readValue(json, KisSectorIndexResponse.class);

            // Assert
            assertThat(response.output2()).hasSize(1);
            assertThat(response.output2().getFirst())
                    .extracting(
                            KisSectorIndexResponse.SectorIndexRow::stckBsopDate,
                            KisSectorIndexResponse.SectorIndexRow::bstpNmixOprc,
                            KisSectorIndexResponse.SectorIndexRow::bstpNmixHgpr,
                            KisSectorIndexResponse.SectorIndexRow::bstpNmixLwpr,
                            KisSectorIndexResponse.SectorIndexRow::bstpNmixPrpr,
                            KisSectorIndexResponse.SectorIndexRow::acmlVol,
                            KisSectorIndexResponse.SectorIndexRow::acmlTrPbmn)
                    .containsExactly(
                            "20260612",
                            "8100.50",
                            "8200.30",
                            "8050.10",
                            "8123.62",
                            "350000000",
                            "5500000000000");
        }

        @Test
        @DisplayName("미선언 필드는 ignoreUnknown=true로 무시된다")
        void deserialize_unknownFields_ignored() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{"unknown":"x"},"output2":[
                      {"stck_bsop_date":"20260612","bstp_nmix_prpr":"8123.62",
                       "some_future_field":"ignored","acml_vol":"100","acml_tr_pbmn":"500",
                       "bstp_nmix_oprc":"8000","bstp_nmix_hgpr":"8200","bstp_nmix_lwpr":"7990"}
                    ]}
                    """;

            KisSectorIndexResponse response =
                    objectMapper.readValue(json, KisSectorIndexResponse.class);

            assertThat(response.output2()).hasSize(1);
            assertThat(response.output2().getFirst().bstpNmixPrpr()).isEqualTo("8123.62");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output2 가 null 이면 빈 리스트로 대체된다")
        void nullOutput2_becomesEmptyList() {
            KisSectorIndexResponse response =
                    new KisSectorIndexResponse("0", "MCA00000", "정상", null);

            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("output2 는 불변 리스트다")
        void output2_isImmutable() {
            KisSectorIndexResponse.SectorIndexRow row =
                    new KisSectorIndexResponse.SectorIndexRow(
                            "20260612", "8100", "8200", "8050", "8123", "100", "500");
            KisSectorIndexResponse response =
                    new KisSectorIndexResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output2().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
