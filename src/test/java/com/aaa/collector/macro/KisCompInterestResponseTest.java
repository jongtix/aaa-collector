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

@DisplayName("KisCompInterestResponse 역직렬화 단위 테스트")
class KisCompInterestResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output2 금리 지표 행 역직렬화")
    class Output2Deserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[]}
                    """;

            KisCompInterestResponse response =
                    objectMapper.readValue(json, KisCompInterestResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("유효 금리 행 — Y0117 국고채30년 (T0 실측 형태) 모든 필드 매핑")
        void deserialize_validRateRow_Y0117() throws Exception {
            // Arrange — T0 실측 기반 정상 행 (국고채30년)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "bcdt_code":"Y0117",
                        "hts_kor_isnm":"국고채30년",
                        "bond_mnrt_prpr":"2.450",
                        "prdy_vrss_sign":"5",
                        "bond_mnrt_prdy_vrss":"-0.010",
                        "bstp_nmix_prdy_ctrt":"-0.41",
                        "stck_bsop_date":"20260615"
                      }
                    ]}
                    """;

            // Act
            KisCompInterestResponse response =
                    objectMapper.readValue(json, KisCompInterestResponse.class);

            // Assert
            assertThat(response.output2()).hasSize(1);
            assertThat(response.output2().getFirst())
                    .extracting(
                            KisCompInterestResponse.CompInterestRow::bcdtCode,
                            KisCompInterestResponse.CompInterestRow::htsKorIsnm,
                            KisCompInterestResponse.CompInterestRow::bondMnrtPrpr,
                            KisCompInterestResponse.CompInterestRow::prdyVrssSign,
                            KisCompInterestResponse.CompInterestRow::bondMnrtPrdyVrss,
                            KisCompInterestResponse.CompInterestRow::bstpNmixPrdyCtrt,
                            KisCompInterestResponse.CompInterestRow::stckBsopDate)
                    .containsExactly(
                            "Y0117", "국고채30년", "2.450", "5", "-0.010", "-0.41", "20260615");
        }

        @Test
        @DisplayName("malformed 선두 행 — 필드 시프트 행도 역직렬화 정상 수행, 2행 파싱됨")
        void deserialize_malformedLeadingRow_bothRowsParsed() throws Exception {
            // Arrange — T0 실측: 선두 행은 hts_kor_isnm에 "Y0117" 같은 bcdt_code가 들어옴(필드 시프트)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "bcdt_code":"Y0101",
                        "hts_kor_isnm":"Y0117",
                        "bond_mnrt_prpr":"Y0199",
                        "prdy_vrss_sign":"",
                        "bond_mnrt_prdy_vrss":"",
                        "bstp_nmix_prdy_ctrt":"",
                        "stck_bsop_date":"20260615"
                      },
                      {
                        "bcdt_code":"Y0117",
                        "hts_kor_isnm":"국고채30년",
                        "bond_mnrt_prpr":"2.450",
                        "prdy_vrss_sign":"5",
                        "bond_mnrt_prdy_vrss":"-0.010",
                        "bstp_nmix_prdy_ctrt":"-0.41",
                        "stck_bsop_date":"20260615"
                      }
                    ]}
                    """;

            // Act
            KisCompInterestResponse response =
                    objectMapper.readValue(json, KisCompInterestResponse.class);

            // Assert — 2행 모두 파싱됨, 서비스 레이어에서 malformed 판별·skip (REQ-BATCH3-070)
            assertThat(response.output2()).hasSize(2);
            KisCompInterestResponse.CompInterestRow malformed = response.output2().getFirst();
            assertThat(malformed.bcdtCode()).isEqualTo("Y0101");
            assertThat(malformed.htsKorIsnm()).isEqualTo("Y0117"); // 필드 시프트 확인
            assertThat(malformed.bondMnrtPrpr()).isEqualTo("Y0199"); // 비숫자 확인
        }

        @Test
        @DisplayName("malformed 선두 행 — 유효 행은 정상 값 보유")
        void deserialize_malformedLeadingRow_validRowIntact() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "bcdt_code":"Y0101",
                        "hts_kor_isnm":"Y0117",
                        "bond_mnrt_prpr":"Y0199",
                        "prdy_vrss_sign":"",
                        "bond_mnrt_prdy_vrss":"",
                        "bstp_nmix_prdy_ctrt":"",
                        "stck_bsop_date":"20260615"
                      },
                      {
                        "bcdt_code":"Y0117",
                        "hts_kor_isnm":"국고채30년",
                        "bond_mnrt_prpr":"2.450",
                        "prdy_vrss_sign":"5",
                        "bond_mnrt_prdy_vrss":"-0.010",
                        "bstp_nmix_prdy_ctrt":"-0.41",
                        "stck_bsop_date":"20260615"
                      }
                    ]}
                    """;

            KisCompInterestResponse response =
                    objectMapper.readValue(json, KisCompInterestResponse.class);

            KisCompInterestResponse.CompInterestRow valid = response.output2().get(1);
            assertThat(valid.bcdtCode()).isEqualTo("Y0117");
            assertThat(valid.bondMnrtPrpr()).isEqualTo("2.450");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output2 가 null 이면 빈 리스트로 대체된다")
        void nullOutput2_becomesEmptyList() {
            KisCompInterestResponse response =
                    new KisCompInterestResponse("0", "MCA00000", "정상", null);

            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("output2 는 불변 리스트다")
        void output2_isImmutable() {
            KisCompInterestResponse.CompInterestRow row =
                    new KisCompInterestResponse.CompInterestRow(
                            "Y0117", "국고채30년", "2.450", "5", "-0.010", "-0.41", "20260615");
            KisCompInterestResponse response =
                    new KisCompInterestResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output2().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
