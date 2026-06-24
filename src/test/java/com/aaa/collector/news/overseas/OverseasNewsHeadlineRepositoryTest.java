package com.aaa.collector.news.overseas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("OverseasNewsHeadlineRepository 통합 테스트 (멱등 INSERT IGNORE)")
class OverseasNewsHeadlineRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private OverseasNewsHeadlineRepository repository;

    private OverseasNewsHeadline buildHeadline(String newsKey, String title) {
        return OverseasNewsHeadline.builder()
                .newsKey(newsKey)
                .publishedAt(LocalDateTime.of(2026, 6, 24, 12, 26, 17))
                .infoGb("e")
                .classCd("04")
                .className("ETF")
                .source("글로벌ETF")
                .nationCd("US")
                .exchangeCd("AMS")
                .symbol("BUFZ")
                .symbolName("버퍼형 ETF")
                .title(title)
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-OVE-042/060)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨 (table-not-found 없음)")
        void newRow_insertsOne() {
            repository.insertIgnoreDuplicate(buildHeadline("ICH793864", "버퍼형 ETF 뉴스"));

            assertThat(repository.countAll()).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 news_key 중복 삽입 — 행 수 불변, UPDATE 미발생 (inclusive 커서 경계 행 흡수)")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            String newsKey = "ICH700100";
            String originalTitle = "원본 해외 뉴스 제목";
            repository.insertIgnoreDuplicate(buildHeadline(newsKey, originalTitle));

            // Act — 동일 news_key로 다른 제목 재삽입 (inclusive DATA_DT/DATA_TM 커서 경계 행 시나리오)
            repository.insertIgnoreDuplicate(buildHeadline(newsKey, "변경된 제목 — 저장되면 안 됨"));

            // Assert
            assertThat(repository.countAll()).isEqualTo(1L);
            OverseasNewsHeadline saved =
                    repository.findAll().stream()
                            .filter(n -> n.getNewsKey().equals(newsKey))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getTitle()).isEqualTo(originalTitle);
        }

        @Test
        @DisplayName("서로 다른 news_key — 각각 독립 삽입")
        void differentNewsKeys_insertsDistinctRows() {
            repository.insertIgnoreDuplicate(buildHeadline("ICH000001", "뉴스1"));
            repository.insertIgnoreDuplicate(buildHeadline("ICH000002", "뉴스2"));

            assertThat(repository.countAll()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("종목 무관 뉴스 — 빈 문자열 symbol/symbol_name/exchange_cd 저장 (REQ-OVE-047)")
    class BlankSymbolPersistence {

        @Test
        @DisplayName("빈 symbol 행 — 빈 문자열 그대로 저장 (NULL 정규화 안 함)")
        void blankSymbolRow_persistsEmptyString() {
            // Arrange — 거시·원자재 뉴스 (symb/symb_name/exchange_cd 빈 문자열)
            OverseasNewsHeadline macroNews =
                    OverseasNewsHeadline.builder()
                            .newsKey("ICH793854")
                            .publishedAt(LocalDateTime.of(2026, 6, 24, 11, 22, 35))
                            .infoGb("e")
                            .classCd("03")
                            .className("Commodity")
                            .source("글로벌ETF")
                            .nationCd("US")
                            .exchangeCd("")
                            .symbol("")
                            .symbolName("")
                            .title("화타이증권, 중장기 알루미늄 가격 낙관 전망")
                            .build();

            // Act
            repository.insertIgnoreDuplicate(macroNews);

            // Assert
            OverseasNewsHeadline saved =
                    repository.findAll().stream()
                            .filter(n -> "ICH793854".equals(n.getNewsKey()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getSymbol()).isEmpty();
            assertThat(saved.getSymbolName()).isEmpty();
            assertThat(saved.getExchangeCd()).isEmpty();
        }
    }
}
