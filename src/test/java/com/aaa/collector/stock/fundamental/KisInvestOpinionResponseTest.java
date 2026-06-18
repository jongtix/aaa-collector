package com.aaa.collector.stock.fundamental;

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

@DisplayName("KisInvestOpinionResponse 역직렬화 단위 테스트")
class KisInvestOpinionResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    @DisplayName("output 행 12개 매핑 필드 역직렬화 + 미매핑 필드 무시")
    void deserialize_mapsTwelveFields_ignoresUnknown() throws Exception {
        String json =
                """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {
                    "stck_bsop_date":"20260612",
                    "invt_opnn":"매수",
                    "invt_opnn_cls_code":"2",
                    "rgbf_invt_opnn":"중립",
                    "rgbf_invt_opnn_cls_code":"3",
                    "mbcr_name":"OO증권",
                    "hts_goal_prc":"95000",
                    "stck_prdy_clpr":"82000",
                    "stck_nday_esdg":"13000",
                    "nday_dprt":"15.85",
                    "stft_esdg":"500",
                    "dprt":"0.61",
                    "unknown_field":"ignored"
                  }
                ]}
                """;

        KisInvestOpinionResponse response =
                objectMapper.readValue(json, KisInvestOpinionResponse.class);

        assertThat(response.output()).hasSize(1);
        assertThat(response.output().getFirst())
                .extracting(
                        KisInvestOpinionResponse.InvestOpinionRow::stckBsopDate,
                        KisInvestOpinionResponse.InvestOpinionRow::invtOpnn,
                        KisInvestOpinionResponse.InvestOpinionRow::invtOpnnClsCode,
                        KisInvestOpinionResponse.InvestOpinionRow::rgbfInvtOpnn,
                        KisInvestOpinionResponse.InvestOpinionRow::rgbfInvtOpnnClsCode,
                        KisInvestOpinionResponse.InvestOpinionRow::mbcrName,
                        KisInvestOpinionResponse.InvestOpinionRow::htsGoalPrc,
                        KisInvestOpinionResponse.InvestOpinionRow::stckPrdyClpr,
                        KisInvestOpinionResponse.InvestOpinionRow::stckNdayEsdg,
                        KisInvestOpinionResponse.InvestOpinionRow::ndayDprt,
                        KisInvestOpinionResponse.InvestOpinionRow::stftEsdg,
                        KisInvestOpinionResponse.InvestOpinionRow::dprt)
                .containsExactly(
                        "20260612",
                        "매수",
                        "2",
                        "중립",
                        "3",
                        "OO증권",
                        "95000",
                        "82000",
                        "13000",
                        "15.85",
                        "500",
                        "0.61");
    }

    @Test
    @DisplayName("빈 회원사명 행도 정상 역직렬화 (mbcr_name='')")
    void deserialize_emptyInstitution() throws Exception {
        String json =
                """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {"stck_bsop_date":"20260612","mbcr_name":"","hts_goal_prc":"95000","stck_prdy_clpr":"82000"}
                ]}
                """;

        KisInvestOpinionResponse response =
                objectMapper.readValue(json, KisInvestOpinionResponse.class);

        assertThat(response.output().getFirst().mbcrName()).isEmpty();
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output 가 null 이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisInvestOpinionResponse response =
                    new KisInvestOpinionResponse("0", "MCA00000", "정상", null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 는 불변 리스트다")
        void output_isImmutable() {
            KisInvestOpinionResponse.InvestOpinionRow row =
                    new KisInvestOpinionResponse.InvestOpinionRow(
                            "20260612", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
            KisInvestOpinionResponse response =
                    new KisInvestOpinionResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
