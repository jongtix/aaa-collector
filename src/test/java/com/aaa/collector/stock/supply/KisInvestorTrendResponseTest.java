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

@DisplayName("KisInvestorTrendResponse 역직렬화 단위 테스트")
class KisInvestorTrendResponseTest {

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

            KisInvestorTrendResponse response =
                    objectMapper.readValue(json, KisInvestorTrendResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("11분류 응답에서 3분류(외국인/기관계/개인) 필드만 역직렬화하고 나머지는 무시한다")
        void deserialize_onlyThreeCategories_ignoresOthers() throws Exception {
            // Arrange — 11분류 전체 필드를 포함한 응답 (8분류는 무시되어야 함)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output2":[
                      {
                        "stck_bsop_date":"20260605",
                        "frgn_ntby_qty":"1000",
                        "orgn_ntby_qty":"-2000",
                        "prsn_ntby_qty":"3000",
                        "frgn_ntby_tr_pbmn":"7500",
                        "orgn_ntby_tr_pbmn":"-15000",
                        "prsn_ntby_tr_pbmn":"22500",
                        "acml_vol":"5000000",
                        "acml_tr_pbmn":"375000",
                        "scrt_ntby_qty":"999",
                        "ivtr_ntby_qty":"888",
                        "bank_ntby_qty":"777",
                        "etc_corp_ntby_vol":"666"
                      }
                    ]}
                    """;

            // Act
            KisInvestorTrendResponse response =
                    objectMapper.readValue(json, KisInvestorTrendResponse.class);

            // Assert — extracting()으로 매핑 필드 전건을 단일 assert로 검증(8분류 무시는 컴파일·역직렬화 성공으로 입증)
            assertThat(response.output2()).hasSize(1);
            assertThat(response.output2().getFirst())
                    .extracting(
                            KisInvestorTrendResponse.InvestorTrendRow::stckBsopDate,
                            KisInvestorTrendResponse.InvestorTrendRow::frgnNtbyQty,
                            KisInvestorTrendResponse.InvestorTrendRow::orgnNtbyQty,
                            KisInvestorTrendResponse.InvestorTrendRow::prsnNtbyQty,
                            KisInvestorTrendResponse.InvestorTrendRow::frgnNtbyTrPbmn,
                            KisInvestorTrendResponse.InvestorTrendRow::orgnNtbyTrPbmn,
                            KisInvestorTrendResponse.InvestorTrendRow::prsnNtbyTrPbmn,
                            KisInvestorTrendResponse.InvestorTrendRow::acmlVol,
                            KisInvestorTrendResponse.InvestorTrendRow::acmlTrPbmn)
                    .containsExactly(
                            "20260605",
                            "1000",
                            "-2000",
                            "3000",
                            "7500",
                            "-15000",
                            "22500",
                            "5000000",
                            "375000");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output2 가 null 이면 빈 리스트로 대체된다")
        void nullOutput2_becomesEmptyList() {
            KisInvestorTrendResponse response =
                    new KisInvestorTrendResponse("0", "MCA00000", "정상", null);

            assertThat(response.output2()).isEmpty();
        }

        @Test
        @DisplayName("output2 는 불변 리스트다")
        void output2_isImmutable() {
            KisInvestorTrendResponse.InvestorTrendRow row =
                    new KisInvestorTrendResponse.InvestorTrendRow(
                            "20260605", "1", "2", "3", "4", "5", "6", "7", "8");
            KisInvestorTrendResponse response =
                    new KisInvestorTrendResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output2().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
