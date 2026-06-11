package com.aaa.collector.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NewsHeadline builder 검증")
class NewsHeadlineTest {

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("serialNo와 publishedAt이 설정된다")
        void newsHeadline_serialNoAndPublishedAtSet() {
            LocalDateTime publishedAt = LocalDateTime.of(2026, 6, 11, 9, 0, 0);

            NewsHeadline headline =
                    NewsHeadline.builder()
                            .serialNo("20260611001")
                            .publishedAt(publishedAt)
                            .title("삼성전자 2분기 실적 발표")
                            .build();

            assertThat(headline.getSerialNo()).isEqualTo("20260611001");
            assertThat(headline.getPublishedAt()).isEqualTo(publishedAt);
        }

        @Test
        @DisplayName("providerCode, title, categoryCode, source가 설정된다")
        void newsHeadline_metaFieldsSet() {
            NewsHeadline headline =
                    NewsHeadline.builder()
                            .serialNo("20260611002")
                            .publishedAt(LocalDateTime.of(2026, 6, 11, 9, 0, 0))
                            .providerCode("A")
                            .title("삼성전자 2분기 실적 발표")
                            .categoryCode("00000001")
                            .source("연합뉴스")
                            .build();

            assertThat(headline.getProviderCode()).isEqualTo("A");
            assertThat(headline.getTitle()).isEqualTo("삼성전자 2분기 실적 발표");
            assertThat(headline.getCategoryCode()).isEqualTo("00000001");
            assertThat(headline.getSource()).isEqualTo("연합뉴스");
        }

        @Test
        @DisplayName("종목코드 5개를 설정하면 모두 저장된다")
        void newsHeadline_fiveStockCodesSet() {
            NewsHeadline headline =
                    NewsHeadline.builder()
                            .serialNo("20260611003")
                            .publishedAt(LocalDateTime.of(2026, 6, 11, 10, 0, 0))
                            .title("반도체 관련주 일제 강세")
                            .stockCode1("005930")
                            .stockCode2("000660")
                            .stockCode3("042700")
                            .stockCode4("267260")
                            .stockCode5("029780")
                            .build();

            assertThat(headline.getStockCode1()).isEqualTo("005930");
            assertThat(headline.getStockCode2()).isEqualTo("000660");
            assertThat(headline.getStockCode5()).isEqualTo("029780");
        }

        @Test
        @DisplayName("종목코드를 설정하지 않으면 null이다")
        void newsHeadline_stockCodesNullByDefault() {
            NewsHeadline headline =
                    NewsHeadline.builder()
                            .serialNo("20260611004")
                            .publishedAt(LocalDateTime.of(2026, 6, 11, 11, 0, 0))
                            .title("제목 없음")
                            .build();

            assertThat(headline.getStockCode1()).isNull();
            assertThat(headline.getStockCode3()).isNull();
        }
    }
}
