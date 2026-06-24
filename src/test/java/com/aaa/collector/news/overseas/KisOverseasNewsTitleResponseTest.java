package com.aaa.collector.news.overseas;

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

@DisplayName("KisOverseasNewsTitleResponse 역직렬화 단위 테스트")
class KisOverseasNewsTitleResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Nested
    @DisplayName("outblock1 배열 역직렬화 (T0 실측: 12필드, 10건/page)")
    class OutblockDeserialization {

        @Test
        @DisplayName("rt_cd/msg_cd/msg1 공통 필드 + 빈 outblock1 매핑")
        void deserialize_commonFields() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","outblock1":[]}
                    """;

            KisOverseasNewsTitleResponse response =
                    objectMapper.readValue(json, KisOverseasNewsTitleResponse.class);

            assertThat(response.rtCd()).isEqualTo("0");
            assertThat(response.msgCd()).isEqualTo("MCA00000");
            assertThat(response.msg1()).isEqualTo("정상");
            assertThat(response.outblock1()).isEmpty();
        }

        @Test
        @DisplayName("종목 연결 뉴스 행 — 12필드 snake_case→camelCase 전부 매핑")
        void deserialize_stockRow_allFieldsMapped() throws Exception {
            // Arrange — T0 실측 형태 (종목 연결 뉴스: symb/symb_name/exchange_cd 채워짐)
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","outblock1":[
                      {
                        "info_gb":"e",
                        "news_key":"ICH793864",
                        "data_dt":"20260624",
                        "data_tm":"122617",
                        "class_cd":"04",
                        "class_name":"ETF",
                        "source":"글로벌ETF",
                        "nation_cd":"US",
                        "exchange_cd":"AMS",
                        "symb":"BUFZ",
                        "symb_name":"버퍼형 ETF",
                        "title":"[ETF 투자전략] 버퍼형 ETF, 변동성 장세 속 대안 부상"
                      }
                    ]}
                    """;

            // Act
            KisOverseasNewsTitleResponse response =
                    objectMapper.readValue(json, KisOverseasNewsTitleResponse.class);

            // Assert
            assertThat(response.outblock1()).hasSize(1);
            assertThat(response.outblock1().getFirst())
                    .extracting(
                            KisOverseasNewsTitleResponse.NewsRow::infoGb,
                            KisOverseasNewsTitleResponse.NewsRow::newsKey,
                            KisOverseasNewsTitleResponse.NewsRow::dataDt,
                            KisOverseasNewsTitleResponse.NewsRow::dataTm,
                            KisOverseasNewsTitleResponse.NewsRow::classCd,
                            KisOverseasNewsTitleResponse.NewsRow::className,
                            KisOverseasNewsTitleResponse.NewsRow::source,
                            KisOverseasNewsTitleResponse.NewsRow::nationCd,
                            KisOverseasNewsTitleResponse.NewsRow::exchangeCd,
                            KisOverseasNewsTitleResponse.NewsRow::symb,
                            KisOverseasNewsTitleResponse.NewsRow::symbName,
                            KisOverseasNewsTitleResponse.NewsRow::title)
                    .containsExactly(
                            "e",
                            "ICH793864",
                            "20260624",
                            "122617",
                            "04",
                            "ETF",
                            "글로벌ETF",
                            "US",
                            "AMS",
                            "BUFZ",
                            "버퍼형 ETF",
                            "[ETF 투자전략] 버퍼형 ETF, 변동성 장세 속 대안 부상");
        }

        @Test
        @DisplayName("종목 무관 뉴스(거시·원자재) — symb/symb_name/exchange_cd 빈 문자열 그대로 보존 (T0 실측)")
        void deserialize_macroRow_blankSymbPreserved() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","outblock1":[
                      {
                        "info_gb":"e",
                        "news_key":"ICH793854",
                        "data_dt":"20260624",
                        "data_tm":"112235",
                        "class_cd":"03",
                        "class_name":"Commodity",
                        "source":"글로벌ETF",
                        "nation_cd":"US",
                        "exchange_cd":"",
                        "symb":"",
                        "symb_name":"",
                        "title":"화타이증권, 중장기 알루미늄 가격 낙관 전망"
                      }
                    ]}
                    """;

            KisOverseasNewsTitleResponse response =
                    objectMapper.readValue(json, KisOverseasNewsTitleResponse.class);

            KisOverseasNewsTitleResponse.NewsRow row = response.outblock1().getFirst();
            assertThat(row.symb()).isEmpty();
            assertThat(row.symbName()).isEmpty();
            assertThat(row.exchangeCd()).isEmpty();
            assertThat(row.newsKey()).isEqualTo("ICH793854");
        }

        @Test
        @DisplayName("미선언 필드는 ignoreUnknown=true로 무시된다")
        void deserialize_unknownField_ignored() throws Exception {
            String json =
                    """
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","outblock1":[
                      {
                        "info_gb":"e","news_key":"ICH700001","data_dt":"20260624","data_tm":"090000",
                        "class_cd":"01","class_name":"Market","source":"src","nation_cd":"US",
                        "exchange_cd":"NAS","symb":"AAPL","symb_name":"Apple","title":"제목",
                        "unknown_field":"ignored"
                      }
                    ]}
                    """;

            KisOverseasNewsTitleResponse response =
                    objectMapper.readValue(json, KisOverseasNewsTitleResponse.class);

            assertThat(response.outblock1()).hasSize(1);
            assertThat(response.outblock1().getFirst().newsKey()).isEqualTo("ICH700001");
        }
    }

    @Nested
    @DisplayName("방어적 복사")
    class DefensiveCopy {

        @Test
        @DisplayName("outblock1 이 null 이면 빈 리스트로 대체된다")
        void nullOutblock_becomesEmptyList() {
            KisOverseasNewsTitleResponse response =
                    new KisOverseasNewsTitleResponse("0", "MCA00000", "정상", null);

            assertThat(response.outblock1()).isEmpty();
        }

        @Test
        @DisplayName("outblock1 은 불변 리스트다")
        void outblock_isImmutable() {
            KisOverseasNewsTitleResponse.NewsRow row =
                    new KisOverseasNewsTitleResponse.NewsRow(
                            "e",
                            "ICH1",
                            "20260624",
                            "090000",
                            "01",
                            "Market",
                            "src",
                            "US",
                            "NAS",
                            "AAPL",
                            "Apple",
                            "제목");
            KisOverseasNewsTitleResponse response =
                    new KisOverseasNewsTitleResponse(
                            "0", "MCA00000", "정상", new ArrayList<>(List.of(row)));

            assertThatThrownBy(() -> response.outblock1().add(row))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
