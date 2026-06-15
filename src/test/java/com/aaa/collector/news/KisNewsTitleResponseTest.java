package com.aaa.collector.news;

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

@DisplayName("KisNewsTitleResponse 역직렬화 단위 테스트")
class KisNewsTitleResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("output 배열 행 역직렬화 (T0 실측: 배열, 40건/page)")
    class OutputDeserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[]}
                    """;

            KisNewsTitleResponse response =
                    objectMapper.readValue(json, KisNewsTitleResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 뉴스 행 역직렬화 — iscd1~5 매핑, snake_case→camelCase 확인")
        void deserialize_outputRow_iscd1To5Mapped() throws Exception {
            // Arrange — T0 실측 형태 (iscd6~10·kor_isnm1~10 존재하나 ignoreUnknown으로 무시)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                      {
                        "cntt_usiq_srno":"1234567890123456789",
                        "news_ofer_entp_code":"1",
                        "data_dt":"20260615",
                        "data_tm":"093000",
                        "hts_pbnt_titl_cntt":"삼성전자 1분기 실적 발표",
                        "news_lrdv_code":"01",
                        "dorg":"연합뉴스",
                        "iscd1":"005930",
                        "iscd2":"000660",
                        "iscd3":"",
                        "iscd4":"",
                        "iscd5":"",
                        "iscd6":"",
                        "iscd7":"",
                        "iscd8":"",
                        "iscd9":"",
                        "iscd10":"",
                        "kor_isnm1":"삼성전자",
                        "kor_isnm2":"SK하이닉스",
                        "kor_isnm3":"",
                        "kor_isnm4":"",
                        "kor_isnm5":""
                      }
                    ]}
                    """;

            // Act
            KisNewsTitleResponse response =
                    objectMapper.readValue(json, KisNewsTitleResponse.class);

            // Assert — iscd1~5만 매핑, iscd6~10·kor_isnm1~10은 무시
            assertThat(response.output()).hasSize(1);
            assertThat(response.output().getFirst())
                    .extracting(
                            KisNewsTitleResponse.NewsTitleRow::cnttUsiqSrno,
                            KisNewsTitleResponse.NewsTitleRow::newsOferEntpCode,
                            KisNewsTitleResponse.NewsTitleRow::dataDt,
                            KisNewsTitleResponse.NewsTitleRow::dataTm,
                            KisNewsTitleResponse.NewsTitleRow::htsPbntTitlCntt,
                            KisNewsTitleResponse.NewsTitleRow::newsLrdvCode,
                            KisNewsTitleResponse.NewsTitleRow::dorg,
                            KisNewsTitleResponse.NewsTitleRow::iscd1,
                            KisNewsTitleResponse.NewsTitleRow::iscd2,
                            KisNewsTitleResponse.NewsTitleRow::iscd3,
                            KisNewsTitleResponse.NewsTitleRow::iscd4,
                            KisNewsTitleResponse.NewsTitleRow::iscd5)
                    .containsExactly(
                            "1234567890123456789",
                            "1",
                            "20260615",
                            "093000",
                            "삼성전자 1분기 실적 발표",
                            "01",
                            "연합뉴스",
                            "005930",
                            "000660",
                            "",
                            "",
                            "");
        }

        @Test
        @DisplayName("iscd6~10·kor_isnm1~10 미선언 필드는 ignoreUnknown=true로 무시된다")
        void deserialize_iscd6To10_ignored() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                      {
                        "cntt_usiq_srno":"9999999999999999999",
                        "news_ofer_entp_code":"2",
                        "data_dt":"20260615",
                        "data_tm":"100000",
                        "hts_pbnt_titl_cntt":"시황 뉴스",
                        "news_lrdv_code":"02",
                        "dorg":"뉴시스",
                        "iscd1":"035420",
                        "iscd2":"","iscd3":"","iscd4":"","iscd5":"",
                        "iscd6":"999999","iscd7":"888888","iscd8":"","iscd9":"","iscd10":"",
                        "kor_isnm1":"NAVER","kor_isnm6":"ignored"
                      }
                    ]}
                    """;

            KisNewsTitleResponse response =
                    objectMapper.readValue(json, KisNewsTitleResponse.class);

            assertThat(response.output()).hasSize(1);
            assertThat(response.output().getFirst().iscd1()).isEqualTo("035420");
            assertThat(response.output().getFirst().cnttUsiqSrno())
                    .isEqualTo("9999999999999999999");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("output 이 null 이면 빈 리스트로 대체된다")
        void nullOutput_becomesEmptyList() {
            KisNewsTitleResponse response = new KisNewsTitleResponse("0", "MCA00000", "정상", null);

            assertThat(response.output()).isEmpty();
        }

        @Test
        @DisplayName("output 은 불변 리스트다")
        void output_isImmutable() {
            KisNewsTitleResponse.NewsTitleRow row =
                    new KisNewsTitleResponse.NewsTitleRow(
                            "1234567890123456789",
                            "1",
                            "20260615",
                            "093000",
                            "제목",
                            "01",
                            "연합뉴스",
                            "005930",
                            "",
                            "",
                            "",
                            "");
            KisNewsTitleResponse response =
                    new KisNewsTitleResponse("0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.output().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
