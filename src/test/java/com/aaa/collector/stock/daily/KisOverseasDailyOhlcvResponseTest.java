package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KisOverseasDailyOhlcvResponse 역직렬화 단위 테스트 (명세22 HHDFS76240000)")
class KisOverseasDailyOhlcvResponseTest {

    /** 명세22 실측 응답(AAPL/NAS) — output2 1건만 표시, 미적재 필드 포함. */
    private static final String ACTUAL_JSON =
            """
            {
              "output1": {"rsym": "DNASAAPL", "zdiv": "4", "nrec": "100"},
              "output2": [
                {
                  "xymd": "20260617",
                  "clos": "295.9500",
                  "sign": "5",
                  "diff": "3.2900",
                  "rate": "-1.10",
                  "open": "300.8450",
                  "high": "302.0700",
                  "low": "294.3600",
                  "tvol": "42745060",
                  "tamt": "12697950974",
                  "pbid": "297.0000",
                  "vbid": "1000",
                  "pask": "297.1400",
                  "vask": "3360"
                }
              ],
              "rt_cd": "0",
              "msg_cd": "MCA00000",
              "msg1": "정상처리 되었습니다."
            }
            """;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("실측 응답 역직렬화 (REQ-OVOH-003/016/017)")
    class Deserialization {

        @Test
        @DisplayName("output1(zdiv/nrec) 매핑")
        void deserialize_output1_mapped() throws Exception {
            KisOverseasDailyOhlcvResponse response =
                    objectMapper.readValue(ACTUAL_JSON, KisOverseasDailyOhlcvResponse.class);

            assertThat(response.output1().zdiv()).isEqualTo("4");
            assertThat(response.output1().nrec()).isEqualTo("100");
        }

        @Test
        @DisplayName("output2 적재 7필드(xymd/OHLC/tvol/tamt) 매핑 — 미적재 필드 무시")
        void deserialize_output2_loadedFieldsMapped() throws Exception {
            KisOverseasDailyOhlcvResponse response =
                    objectMapper.readValue(ACTUAL_JSON, KisOverseasDailyOhlcvResponse.class);

            assertThat(response.output2()).hasSize(1);
            KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row = response.output2().getFirst();
            assertThat(row.xymd()).isEqualTo("20260617");
            assertThat(row.open()).isEqualTo("300.8450");
            assertThat(row.clos()).isEqualTo("295.9500");
            assertThat(row.tvol()).isEqualTo("42745060");
        }

        @Test
        @DisplayName("output2 고가/저가/거래대금 매핑")
        void deserialize_output2_highLowTamtMapped() throws Exception {
            KisOverseasDailyOhlcvResponse response =
                    objectMapper.readValue(ACTUAL_JSON, KisOverseasDailyOhlcvResponse.class);

            KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow row = response.output2().getFirst();
            assertThat(row.high()).isEqualTo("302.0700");
            assertThat(row.low()).isEqualTo("294.3600");
            assertThat(row.tamt()).isEqualTo("12697950974");
        }

        @Test
        @DisplayName("빈 output1/output2 — null/빈 리스트 방어 (REQ-OVOH-017 빈응답 가드 기반)")
        void deserialize_emptyOutput_defensiveDefaults() throws Exception {
            // Arrange — GSAT@AMS 실측: rt_cd=0이나 output 비어있음
            String json =
                    """
                    {"rt_cd": "0", "msg_cd": "MCA00000", "msg1": "정상처리 되었습니다."}
                    """;

            // Act
            KisOverseasDailyOhlcvResponse response =
                    objectMapper.readValue(json, KisOverseasDailyOhlcvResponse.class);

            // Assert — output2는 null 대신 빈 리스트, output1은 null 허용
            assertThat(response.output2()).isEmpty();
            assertThat(response.output1()).isNull();
        }
    }
}
