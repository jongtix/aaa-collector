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

@DisplayName("KisFinancialRatioResponse 역직렬화 단위 테스트")
class KisFinancialRatioResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Test
    @DisplayName("output 행 10개 매핑 필드 역직렬화 + 미매핑 필드 무시")
    void deserialize_mapsTenFields_ignoresUnknown() throws Exception {
        String json =
                """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {
                    "stac_yymm":"202603",
                    "grs":"12.5",
                    "bsop_prfi_inrt":"0",
                    "ntin_inrt":"8.1",
                    "roe_val":"15.2",
                    "eps":"6993.00",
                    "sps":"57655",
                    "bps":"71907.00",
                    "rsrv_rate":"1200.5",
                    "lblt_rate":"45.3",
                    "unknown_field":"ignored"
                  }
                ]}
                """;

        KisFinancialRatioResponse response =
                objectMapper.readValue(json, KisFinancialRatioResponse.class);

        assertThat(response.rtCd()).isEqualTo("0");
        assertThat(response.output()).hasSize(1);
        assertThat(response.output().getFirst())
                .extracting(
                        KisFinancialRatioResponse.FinancialRatioRow::stacYymm,
                        KisFinancialRatioResponse.FinancialRatioRow::grs,
                        KisFinancialRatioResponse.FinancialRatioRow::bsopPrfiInrt,
                        KisFinancialRatioResponse.FinancialRatioRow::ntinInrt,
                        KisFinancialRatioResponse.FinancialRatioRow::roeVal,
                        KisFinancialRatioResponse.FinancialRatioRow::eps,
                        KisFinancialRatioResponse.FinancialRatioRow::sps,
                        KisFinancialRatioResponse.FinancialRatioRow::bps,
                        KisFinancialRatioResponse.FinancialRatioRow::rsrvRate,
                        KisFinancialRatioResponse.FinancialRatioRow::lbltRate)
                .containsExactly(
                        "202603",
                        "12.5",
                        "0",
                        "8.1",
                        "15.2",
                        "6993.00",
                        "57655",
                        "71907.00",
                        "1200.5",
                        "45.3");
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output 가 null 이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisFinancialRatioResponse response =
                    new KisFinancialRatioResponse("0", "MCA00000", "정상", null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 는 불변 리스트다")
        void output_isImmutable() {
            KisFinancialRatioResponse.FinancialRatioRow row =
                    new KisFinancialRatioResponse.FinancialRatioRow(
                            "202603", "1", "2", "3", "4", "5", "6", "7", "8", "9");
            KisFinancialRatioResponse response =
                    new KisFinancialRatioResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
