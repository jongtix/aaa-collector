package com.aaa.collector.dart;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.dart.disclosure.Disclosure;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.dart.disclosure.DisclosureRow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * disclosures INSERT IGNORE 멱등성 통합 검증 (Testcontainers MySQL).
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장됨. SPEC-COLLECTOR-DART-001 AC-I1, REQ-DART-011.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("DisclosureIdempotencyIntegrationTest — INSERT IGNORE 멱등성 통합 검증")
class DisclosureIdempotencyIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private DisclosureRepository disclosureRepository;

    private static final long STOCK_ID = 1L;

    private DisclosureRow row(
            String corpCode, String stockCode, String reportNm, String rceptNo, LocalDate rceptDt) {
        return new DisclosureRow(
                STOCK_ID, corpCode, stockCode, "Y", reportNm, rceptNo, "제출인", rceptDt, null, null);
    }

    @Nested
    @DisplayName("신규 행 삽입")
    class NewRow {

        @Test
        @DisplayName("rcept_no 최초 삽입 — 1건 저장")
        void newDisclosure_insertsOne() {
            disclosureRepository.insertIgnore(
                    row("00000001", "000001", "사업보고서", "20260101000001", LocalDate.of(2026, 1, 1)));

            assertThat(disclosureRepository.countByRceptNo("20260101000001")).isEqualTo(1L);
        }

        @Test
        @DisplayName("서로 다른 rcept_no 2건 — 각각 독립 삽입")
        void twoDistinctRceptNo_insertsBoth() {
            disclosureRepository.insertIgnore(
                    row("00000002", "000002", "반기보고서", "20260201000002", LocalDate.of(2026, 2, 1)));
            disclosureRepository.insertIgnore(
                    row("00000002", "000002", "분기보고서", "20260201000003", LocalDate.of(2026, 2, 1)));

            assertThat(disclosureRepository.countByStockId(STOCK_ID)).isGreaterThanOrEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("중복 삽입 — INSERT IGNORE 멱등성")
    class DuplicateRow {

        @Test
        @DisplayName("동일 rcept_no 2회 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicateRceptNo_rowCountUnchanged() {
            // Arrange
            String rceptNo = "20260301000099";
            disclosureRepository.insertIgnore(
                    row("00000003", "000003", "사업보고서", rceptNo, LocalDate.of(2026, 3, 1)));

            // Act — 동일 rcept_no 로 다시 삽입
            disclosureRepository.insertIgnore(
                    new DisclosureRow(
                            STOCK_ID,
                            "00000003",
                            "000003",
                            "Y",
                            "수정된 보고서명",
                            rceptNo,
                            "LG전자변경",
                            LocalDate.of(2026, 3, 2),
                            null,
                            null));

            // Assert — 행 수 불변 (INSERT IGNORE 보장)
            assertThat(disclosureRepository.countByRceptNo(rceptNo)).isEqualTo(1L);
            List<Disclosure> rows = disclosureRepository.findAll();
            Disclosure saved =
                    rows.stream()
                            .filter(d -> rceptNo.equals(d.getRceptNo()))
                            .findFirst()
                            .orElseThrow();
            // 최초 삽입 값 유지 — 갱신 없음
            assertThat(saved.getReportNm()).isEqualTo("사업보고서");
        }
    }
}
