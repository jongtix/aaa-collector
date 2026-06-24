package com.aaa.collector.stock.rights;

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

@DisplayName("KisOverseasRightsResponse 역직렬화 단위 테스트 (TR HHDFS78330900)")
class KisOverseasRightsResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output1 권리 일정 행 역직렬화")
    class Output1Deserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[]}
                    """;

            KisOverseasRightsResponse response =
                    objectMapper.readValue(json, KisOverseasRightsResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 현금배당 행 역직렬화 — 12필드 snake_case→camelCase 매핑 (명세14 AAPL 실측)")
        void deserialize_output1Row_allFieldsMapped() throws Exception {
            // Arrange — 명세 api-specs/kis/14 실측 응답(AAPL 현금배당 1건)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                      {
                        "anno_dt":"20260501",
                        "ca_title":"현금배당",
                        "div_lock_dt":"20260511",
                        "pay_dt":"20260514",
                        "record_dt":"20260511",
                        "validity_dt":"",
                        "local_end_dt":"",
                        "lock_dt":"",
                        "delist_dt":"",
                        "redempt_dt":"",
                        "early_redempt_dt":"",
                        "effective_dt":""
                      }
                    ]}
                    """;

            // Act
            KisOverseasRightsResponse response =
                    objectMapper.readValue(json, KisOverseasRightsResponse.class);

            // Assert
            assertThat(response.output1()).hasSize(1);
            assertThat(response.output1().getFirst())
                    .extracting(
                            KisOverseasRightsResponse.RightsRow::annoDt,
                            KisOverseasRightsResponse.RightsRow::caTitle,
                            KisOverseasRightsResponse.RightsRow::divLockDt,
                            KisOverseasRightsResponse.RightsRow::payDt,
                            KisOverseasRightsResponse.RightsRow::recordDt,
                            KisOverseasRightsResponse.RightsRow::validityDt,
                            KisOverseasRightsResponse.RightsRow::localEndDt,
                            KisOverseasRightsResponse.RightsRow::lockDt,
                            KisOverseasRightsResponse.RightsRow::delistDt,
                            KisOverseasRightsResponse.RightsRow::redemptDt,
                            KisOverseasRightsResponse.RightsRow::earlyRedemptDt,
                            KisOverseasRightsResponse.RightsRow::effectiveDt)
                    .containsExactly(
                            "20260501",
                            "현금배당",
                            "20260511",
                            "20260514",
                            "20260511",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "");
        }

        @Test
        @DisplayName("비현금배당 행(상장폐지)도 정상 역직렬화 — 저장 단계 skip은 서비스 책임")
        void deserialize_nonCashRow_stillMaps() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                      {"ca_title":"상장폐지","delist_dt":"20260620","record_dt":"20260615"}
                    ]}
                    """;

            KisOverseasRightsResponse response =
                    objectMapper.readValue(json, KisOverseasRightsResponse.class);

            assertThat(response.output1()).hasSize(1);
            assertThat(response.output1().getFirst().caTitle()).isEqualTo("상장폐지");
            assertThat(response.output1().getFirst().delistDt()).isEqualTo("20260620");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output1 이 null 이면 빈 리스트로 대체된다")
        void nullOutput1_becomesEmptyList() {
            KisOverseasRightsResponse response =
                    new KisOverseasRightsResponse("0", "MCA00000", "정상", null);

            assertThat(response.output1()).isEmpty();
        }

        @Test
        @DisplayName("output1 은 불변 리스트다")
        void output1_isImmutable() {
            KisOverseasRightsResponse.RightsRow row =
                    new KisOverseasRightsResponse.RightsRow(
                            "20260501",
                            "현금배당",
                            "20260511",
                            "20260514",
                            "20260511",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "");
            KisOverseasRightsResponse response =
                    new KisOverseasRightsResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output1().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
