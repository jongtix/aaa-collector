package com.aaa.collector.dart;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.dart.corpcode.CorpCodeMappingRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDate;
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
 * corp_code_mapping INSERT IGNORE 멱등성 통합 검증 (Testcontainers MySQL).
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장됨. SPEC-COLLECTOR-DART-001 AC-I2, AC-C3, REQ-DART-002.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("CorpCodeMappingIdempotencyIntegrationTest — INSERT IGNORE 멱등성 통합 검증")
@Tag("integration")
class CorpCodeMappingIdempotencyIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private CorpCodeMappingRepository corpCodeMappingRepository;

    @Nested
    @DisplayName("신규 행 삽입")
    class NewRow {

        @Test
        @DisplayName("stock_code 최초 삽입 — 1건 저장")
        void newMapping_insertsOne() {
            corpCodeMappingRepository.insertIgnore(
                    "005930", "00126380", "삼성전자", LocalDate.of(2026, 1, 1));

            assertThat(corpCodeMappingRepository.countByStockCode("005930")).isEqualTo(1L);
        }

        @Test
        @DisplayName("findCorpCodeByStockCode — 삽입 후 조회 성공")
        void newMapping_findCorpCode() {
            corpCodeMappingRepository.insertIgnore(
                    "000660", "00164779", "SK하이닉스", LocalDate.of(2026, 1, 1));

            assertThat(corpCodeMappingRepository.findCorpCodeByStockCode("000660"))
                    .hasValue("00164779");
        }
    }

    @Nested
    @DisplayName("중복 삽입 — INSERT IGNORE 멱등성")
    class DuplicateRow {

        @Test
        @DisplayName("동일 stock_code 2회 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicateStockCode_rowCountUnchanged() {
            // Arrange
            corpCodeMappingRepository.insertIgnore(
                    "035420", "00293886", "네이버", LocalDate.of(2026, 1, 1));

            // Act — 동일 stock_code 로 다시 삽입 (corp_name 변경 시도)
            corpCodeMappingRepository.insertIgnore(
                    "035420", "00293886", "변경된이름", LocalDate.of(2026, 6, 1));

            // Assert — 행 수 불변, 최초 삽입 값 유지
            assertThat(corpCodeMappingRepository.countByStockCode("035420")).isEqualTo(1L);
            assertThat(corpCodeMappingRepository.findAll())
                    .anyMatch(
                            m ->
                                    "035420".equals(m.getStockCode())
                                            && "네이버".equals(m.getCorpName()));
        }
    }
}
