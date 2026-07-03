package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.macro.enums.MacroSource;
import java.math.BigDecimal;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T8 — ECOS/FRED macro_indicators 멱등성 통합 검증 (Testcontainers MySQL).
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장됨. {@link MacroIndicatorRepositoryTest} 패턴 답습.
 *
 * <p>SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-001, REQ-MACRO-EXT-021
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("MacroExtIdempotencyIntegrationTest — ECOS/FRED 멱등성 통합 검증")
@Tag("integration")
class MacroExtIdempotencyIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private MacroIndicatorRepository macroIndicatorRepository;

    private MacroIndicator buildEcosIndicator(String code, LocalDate date, BigDecimal value) {
        return MacroIndicator.builder()
                .indicatorCode(code)
                .source(MacroSource.ECOS)
                .tradeDate(date)
                .value(value)
                .build();
    }

    private MacroIndicator buildFredIndicator(String code, LocalDate date, BigDecimal value) {
        return MacroIndicator.builder()
                .indicatorCode(code)
                .source(MacroSource.FRED)
                .tradeDate(date)
                .value(value)
                .build();
    }

    // ────────────────────────────────────────────────────────────────────
    // ECOS 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ECOS — INSERT IGNORE 멱등성")
    class EcosIdempotency {

        @Test
        @DisplayName("ECOS_BASE_RATE 신규 행 삽입 — 1건 저장")
        void ecosNewRow_insertsOne() {
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(
                            "ECOS_BASE_RATE", LocalDate.of(2026, 6, 20), new BigDecimal("3.50")));

            assertThat(macroIndicatorRepository.countByIndicatorCode("ECOS_BASE_RATE"))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("ECOS_BASE_RATE 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void ecosDuplicateRow_rowCountUnchanged() {
            // Arrange
            String code = "ECOS_BASE_RATE_DUP_TEST";
            LocalDate date = LocalDate.of(2026, 6, 19);
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, date, new BigDecimal("3.50")));

            // Act — 동일 (indicator_code, trade_date)로 2번 삽입
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, date, new BigDecimal("9.99")));

            // Assert — 행 수 불변, UPDATE 미발생 (INSERT IGNORE 보장)
            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(1L);
            MacroIndicator saved =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> code.equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getValue()).isEqualByComparingTo(new BigDecimal("3.50"));
        }

        @Test
        @DisplayName("ECOS_CPI 월별 시리즈 — 서로 다른 월 독립 삽입")
        void ecosCpiDifferentMonths_insertsDistinctRows() {
            String code = "ECOS_CPI_MONTH_TEST";
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, LocalDate.of(2026, 4, 1), new BigDecimal("2.1")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, LocalDate.of(2026, 5, 1), new BigDecimal("2.3")));

            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(2L);
        }

        @Test
        @DisplayName("ECOS_GDP_QOQ 분기별 시리즈 — 서로 다른 분기 독립 삽입")
        void ecosGdpDifferentQuarters_insertsDistinctRows() {
            String code = "ECOS_GDP_QUARTER_TEST";
            // Q1=2026-01-01, Q2=2026-04-01
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, LocalDate.of(2026, 1, 1), new BigDecimal("1.5")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildEcosIndicator(code, LocalDate.of(2026, 4, 1), new BigDecimal("1.8")));

            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(2L);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FRED 멱등성
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FRED — INSERT IGNORE 멱등성")
    class FredIdempotency {

        @Test
        @DisplayName("FRED_DFF 신규 행 삽입 — 1건 저장")
        void fredNewRow_insertsOne() {
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator(
                            "FRED_DFF", LocalDate.of(2026, 6, 20), new BigDecimal("5.33")));

            assertThat(macroIndicatorRepository.countByIndicatorCode("FRED_DFF")).isEqualTo(1L);
        }

        @Test
        @DisplayName("FRED_DFF 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void fredDuplicateRow_rowCountUnchanged() {
            // Arrange
            String code = "FRED_DFF_DUP_TEST";
            LocalDate date = LocalDate.of(2026, 6, 17);
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator(code, date, new BigDecimal("5.33")));

            // Act
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator(code, date, new BigDecimal("9.99")));

            // Assert
            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(1L);
            MacroIndicator saved =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> code.equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getValue()).isEqualByComparingTo(new BigDecimal("5.33"));
        }

        @Test
        @DisplayName("FRED_DFF 주말 행(토·일) — 그대로 저장 (REQ-MACRO-EXT-033)")
        void fredDffWeekendRows_storedWithoutSkip() {
            // 2026-06-13(토), 2026-06-14(일)
            String code = "FRED_DFF_WEEKEND_TEST";
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator(code, LocalDate.of(2026, 6, 13), new BigDecimal("5.33")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator(code, LocalDate.of(2026, 6, 14), new BigDecimal("5.33")));

            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(2L);
        }

        @Test
        @DisplayName("서로 다른 FRED 시리즈 5개 — 각각 독립 삽입")
        void fredFiveSeries_independentInsertion() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator("FRED_DFF_I5", date, new BigDecimal("5.33")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator("FRED_DGS10_I5", date, new BigDecimal("4.23")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator("FRED_CPIAUCSL_I5", date, new BigDecimal("315.12")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator("FRED_A191RL1Q225SBEA_I5", date, new BigDecimal("1.6")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildFredIndicator("FRED_UNRATE_I5", date, new BigDecimal("4.0")));

            assertThat(macroIndicatorRepository.countByIndicatorCode("FRED_DFF_I5")).isEqualTo(1L);
            assertThat(macroIndicatorRepository.countByIndicatorCode("FRED_DGS10_I5"))
                    .isEqualTo(1L);
            assertThat(macroIndicatorRepository.countByIndicatorCode("FRED_CPIAUCSL_I5"))
                    .isEqualTo(1L);
        }
    }
}
