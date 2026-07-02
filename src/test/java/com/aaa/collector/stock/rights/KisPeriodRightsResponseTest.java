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

/**
 * KisPeriodRightsResponse 역직렬화 단위 테스트 (TR CTRGT011R, 명세 api-specs/kis/28).
 *
 * <p>실측 JSON(2026-07-01, isa 단일키/전체조회 페이징)을 그대로 사용해 스키마 함정(최상위 {@code ctx_area_nk50/fk50}, output
 * 배열, 다중통화 필드)을 고정한다.
 */
@DisplayName("KisPeriodRightsResponse 역직렬화 단위 테스트 (TR CTRGT011R)")
class KisPeriodRightsResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output 행 역직렬화 — 실측 예시1 (PDNO=AAPL 단건)")
    class SingleSymbolDeserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 + output 1건 매핑 (api-specs/kis/28 실측1)")
        void deserialize_aaplSingleRow() throws Exception {
            // Arrange — api-specs/kis/28 "실제 호출 예시" 그대로
            String json =
                    """
                    {
                      "output": [
                        {
                          "bass_dt": "20250812",
                          "rght_type_cd": "03",
                          "pdno": "AAPL",
                          "prdt_name": "APPLE INC",
                          "prdt_type_cd": "512",
                          "std_pdno": "US0378331005",
                          "acpl_bass_dt": "20250811",
                          "sbsc_strt_dt": "",
                          "sbsc_end_dt": "",
                          "cash_alct_rt": "26.0000000000",
                          "stck_alct_rt": "0.000000000000",
                          "crcy_cd": "USD",
                          "crcy_cd2": "",
                          "crcy_cd3": "",
                          "crcy_cd4": "",
                          "alct_frcr_unpr": "0.26000",
                          "stkp_dvdn_frcr_amt2": "0.00000",
                          "stkp_dvdn_frcr_amt3": "0.00000",
                          "stkp_dvdn_frcr_amt4": "0.00000",
                          "dfnt_yn": "Y"
                        }
                      ],
                      "rt_cd": "0",
                      "msg_cd": "MCA00000",
                      "msg1": "조회 되었습니다. (마지막 자료)"
                    }
                    """;

            // Act
            KisPeriodRightsResponse response =
                    objectMapper.readValue(json, KisPeriodRightsResponse.class);

            // Assert
            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.output()).hasSize(1);
            assertThat(response.ctxAreaNk50()).isNull();

            KisPeriodRightsResponse.PeriodRightsRow row = response.output().getFirst();
            assertThat(row)
                    .extracting(
                            KisPeriodRightsResponse.PeriodRightsRow::bassDt,
                            KisPeriodRightsResponse.PeriodRightsRow::rghtTypeCd,
                            KisPeriodRightsResponse.PeriodRightsRow::pdno,
                            KisPeriodRightsResponse.PeriodRightsRow::stdPdno,
                            KisPeriodRightsResponse.PeriodRightsRow::acplBassDt,
                            KisPeriodRightsResponse.PeriodRightsRow::cashAlctRt,
                            KisPeriodRightsResponse.PeriodRightsRow::stckAlctRt,
                            KisPeriodRightsResponse.PeriodRightsRow::crcyCd,
                            KisPeriodRightsResponse.PeriodRightsRow::alctFrcrUnpr,
                            KisPeriodRightsResponse.PeriodRightsRow::dfntYn)
                    .containsExactly(
                            "20250812",
                            "03",
                            "AAPL",
                            "US0378331005",
                            "20250811",
                            "26.0000000000",
                            "0.000000000000",
                            "USD",
                            "0.26000",
                            "Y");
        }
    }

    @Nested
    @DisplayName("output 행 역직렬화 — 실측 예시2 (PDNO 공백 전체조회 + 페이징 커서)")
    class GlobalQueryPagingDeserialization {

        @Test
        @DisplayName("최상위 ctx_area_nk50/fk50 커서 + 다중 시장 혼입 3건 매핑 (api-specs/kis/28 실측2)")
        void deserialize_globalQueryWithCursor() throws Exception {
            // Arrange — api-specs/kis/28 "실제 호출 예시 2" 그대로 (홍콩·중국 혼입 포함)
            String json =
                    """
                    {
                      "output": [
                        {"bass_dt": "20260402", "pdno": "01698", "prdt_name": "TME-SW", "prdt_type_cd": "501", "std_pdno": "KYG875771134", "crcy_cd": "USD", "alct_frcr_unpr": "0.12000", "dfnt_yn": "Y"},
                        {"bass_dt": "20260402", "pdno": "03466", "prdt_name": "HS HIGH DIV", "prdt_type_cd": "501", "std_pdno": "HK0001121091", "crcy_cd": "HKD", "alct_frcr_unpr": "0.13000", "dfnt_yn": "Y"},
                        {"bass_dt": "20260403", "pdno": "002920", "prdt_name": "HUIZHOU DESAY SV AUTOMOTIVE CO LTD", "prdt_type_cd": "552", "std_pdno": "CNE1000033C7", "crcy_cd": "CNY", "alct_frcr_unpr": "1.25000", "dfnt_yn": "Y"}
                      ],
                      "ctx_area_nk50": "20260403!^TQQY!^512!^03                           ",
                      "ctx_area_fk50": "03!^02!^20260402!^20260701!^!^                    ",
                      "rt_cd": "0",
                      "msg_cd": "KIOK0460",
                      "msg1": "조회가 계속됩니다..다음버튼을 Click 하십시오.                                   "
                    }
                    """;

            // Act
            KisPeriodRightsResponse response =
                    objectMapper.readValue(json, KisPeriodRightsResponse.class);

            // Assert — 비공백 커서 = 다음 페이지 존재 신호(REQ-ODA-011)
            assertThat(response.output()).hasSize(3);
            assertThat(response.ctxAreaNk50()).isNotBlank();
            assertThat(response.ctxAreaFk50()).isNotBlank();
            assertThat(response.output().get(1).crcyCd()).isEqualTo("HKD");
            assertThat(response.output().get(2).crcyCd()).isEqualTo("CNY");
        }

        @Test
        @DisplayName("빈 output + 빈 커서 — 페이징 종료 신호")
        void deserialize_emptyOutputEmptyCursor_endOfPaging() throws Exception {
            String json =
                    """
                    {"output": [], "ctx_area_nk50": "", "ctx_area_fk50": "", "rt_cd": "0", "msg_cd": "MCA00000", "msg1": "조회 되었습니다."}
                    """;

            KisPeriodRightsResponse response =
                    objectMapper.readValue(json, KisPeriodRightsResponse.class);

            assertThat(response.output()).isEmpty();
            assertThat(response.ctxAreaNk50()).isBlank();
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output이 null이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisPeriodRightsResponse response =
                    new KisPeriodRightsResponse("0", "MCA00000", "정상", null, null, null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output은 불변 리스트다")
        void output_isImmutable() {
            KisPeriodRightsResponse.PeriodRightsRow row =
                    new KisPeriodRightsResponse.PeriodRightsRow(
                            "20250812",
                            "03",
                            "AAPL",
                            "APPLE INC",
                            "512",
                            "US0378331005",
                            "20250811",
                            "",
                            "",
                            "26.0000",
                            "0.0000",
                            "USD",
                            "",
                            "",
                            "",
                            "0.26000",
                            "0.00000",
                            "0.00000",
                            "0.00000",
                            "Y");
            KisPeriodRightsResponse response =
                    new KisPeriodRightsResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)), null, null);

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
