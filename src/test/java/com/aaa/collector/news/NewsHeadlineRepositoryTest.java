package com.aaa.collector.news;

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
@DisplayName("NewsHeadlineRepository 통합 테스트 (멱등 upsert)")
class NewsHeadlineRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private NewsHeadlineRepository newsHeadlineRepository;

    private NewsHeadline buildHeadline(String serialNo, String title) {
        return NewsHeadline.builder()
                .serialNo(serialNo)
                .publishedAt(LocalDateTime.of(2026, 6, 15, 9, 30, 0))
                .providerCode("1")
                .title(title)
                .categoryCode("01")
                .source("연합뉴스")
                .stockCode1("005930")
                .stockCode2("")
                .stockCode3(null)
                .stockCode4(null)
                .stockCode5(null)
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH3-062)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline("1234567890123456789", "삼성전자 실적 발표"));

            assertThat(newsHeadlineRepository.countAll()).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 serial_no 중복 삽입 — 행 수 불변, UPDATE 미발생 (inclusive SRNO 커서 경계 행 흡수)")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            String serialNo = "9876543210987654321";
            String originalTitle = "원본 뉴스 제목";
            newsHeadlineRepository.insertIgnoreDuplicate(buildHeadline(serialNo, originalTitle));

            // Act — 동일 serial_no로 다른 제목 재삽입 (inclusive 커서 경계 행 시나리오)
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline(serialNo, "변경된 제목 — 저장되면 안 됨"));

            // Assert
            assertThat(newsHeadlineRepository.countAll()).isEqualTo(1L);
            NewsHeadline saved =
                    newsHeadlineRepository.findAll().stream()
                            .filter(n -> n.getSerialNo().equals(serialNo))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getTitle()).isEqualTo(originalTitle);
        }

        @Test
        @DisplayName("서로 다른 serial_no — 각각 독립 삽입")
        void differentSerialNos_insertsDistinctRows() {
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline("1111111111111111111", "뉴스1"));
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline("2222222222222222222", "뉴스2"));

            assertThat(newsHeadlineRepository.countAll()).isEqualTo(2L);
        }
    }
}
