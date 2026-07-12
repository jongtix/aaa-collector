package com.aaa.collector.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * [CR-1 회귀 게이트] RENAME 후 국내 뉴스 적재 통합 검증 (Testcontainers MySQL).
 *
 * <p>Flyway V26(`news_headlines`→`domestic_news_headlines` RENAME)이 적용된 실 MySQL 스키마에서 갱신된 native
 * {@code INSERT IGNORE INTO domestic_news_headlines}가 table-not-found 없이 적재되는지, 그리고 동일 키 재삽입이 멱등(행
 * 수 불변)인지 검증한다. T4의 native SQL 리터럴 미갱신(CR-1) 회귀가 해외 검증만으로는 게이트를 빠져나간다는 지적을 닫는다.
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장됨. {@link DomesticNewsHeadlineRepositoryTest} 패턴 답습.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("DomesticNewsHeadlineRepository 회귀 IT — RENAME 후 native INSERT IGNORE 적재")
@Tag("integration")
class DomesticNewsHeadlineRepositoryIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private DomesticNewsHeadlineRepository newsHeadlineRepository;

    private DomesticNewsHeadline buildHeadline(String serialNo, String title) {
        return DomesticNewsHeadline.builder()
                .serialNo(serialNo)
                .publishedAt(LocalDateTime.of(2026, 6, 24, 9, 30, 0))
                .providerCode("1")
                .title(title)
                .categoryCode("01")
                .source("연합뉴스")
                .stockCode1("005930")
                .build();
    }

    @Nested
    @DisplayName("RENAME 후 native INSERT IGNORE — table-not-found 없이 적재")
    class RenameInsert {

        @Test
        @DisplayName("V26 RENAME된 domestic_news_headlines에 단건 삽입 성공 (table-not-found 없음)")
        void insertAfterRenamePersistsOneWithoutTableNotFound() {
            // Act — 갱신된 native 리터럴(INSERT IGNORE INTO domestic_news_headlines)로 적재
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline("8000000000000000001", "RENAME 후 적재 검증"));

            // Assert — table-not-found 없이 1건 저장됨
            assertThat(newsHeadlineRepository.countAll()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("동일 serial_no 재삽입 — 멱등 (행 수 불변)")
    class IdempotentReinsert {

        @Test
        @DisplayName("동일 키 재삽입 — 예외 없음, 행 수 1 유지 (uk_domestic_news_headlines_serial)")
        void duplicateReinsertRowCountStaysOne() {
            // Arrange
            String serialNo = "8000000000000000002";
            newsHeadlineRepository.insertIgnoreDuplicate(buildHeadline(serialNo, "원본 제목"));

            // Act — 동일 serial_no로 다른 제목 재삽입
            newsHeadlineRepository.insertIgnoreDuplicate(
                    buildHeadline(serialNo, "변경된 제목 — 저장되면 안 됨"));

            // Assert — 행 수 불변, UPDATE 미발생 (INSERT IGNORE 보장)
            assertThat(newsHeadlineRepository.countAll()).isEqualTo(1L);
        }
    }
}
