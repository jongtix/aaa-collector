package com.aaa.collector.macro;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.macro.enums.MacroSource;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.SharedMySqlContainer;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("MacroIndicatorRepository 통합 테스트 (멱등 upsert + V21 마이그레이션)")
@Tag("integration")
class MacroIndicatorRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @Autowired private MacroIndicatorRepository macroIndicatorRepository;

    private MacroIndicator buildIndicator(String code, LocalDate date, BigDecimal value) {
        return MacroIndicator.builder()
                .indicatorCode(code)
                .source(MacroSource.KIS)
                .tradeDate(date)
                .value(value)
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH3-032, -043)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(
                            "KIS_RATE_Y0117", LocalDate.of(2026, 6, 12), new BigDecimal("2.450")));

            assertThat(macroIndicatorRepository.countByIndicatorCode("KIS_RATE_Y0117"))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (indicator_code, trade_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            String code = "KIS_RATE_Y0112";
            LocalDate date = LocalDate.of(2026, 6, 12);
            BigDecimal originalValue = new BigDecimal("3.720");
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(code, date, originalValue));

            // Act
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(code, date, new BigDecimal("9.999")));

            // Assert
            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(1L);
            MacroIndicator saved =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> m.getIndicatorCode().equals(code))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getValue()).isEqualByComparingTo(originalValue);
        }

        @Test
        @DisplayName("서로 다른 거래일 — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            String code = "MKTFUND_CUST_DEPOSIT";
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(code, LocalDate.of(2026, 6, 11), new BigDecimal("5350000")));
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(code, LocalDate.of(2026, 6, 12), new BigDecimal("5400000")));

            assertThat(macroIndicatorRepository.countByIndicatorCode(code)).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("AC-14 — JPA 컨버터 왕복 (persist → find → enum 복원)")
    class JpaConverterRoundtrip {

        @Test
        @DisplayName("MacroSource.KIS 로 저장 후 조회 시 동일 enum 값 복원")
        void persistAndFind_macroSourceKis_enumRestored() {
            // Arrange
            MacroIndicator entity =
                    buildIndicator(
                            "CONVERTER_ROUNDTRIP",
                            LocalDate.of(2026, 6, 27),
                            new BigDecimal("1.234"));

            // Act
            macroIndicatorRepository.save(entity);
            MacroIndicator found =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> "CONVERTER_ROUNDTRIP".equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();

            // Assert
            assertThat(found.getSource()).isEqualTo(MacroSource.KIS);
        }
    }

    @Nested
    @DisplayName("V21 마이그레이션 — DECIMAL(24,8) 정밀도 (REQ-BATCH3-045)")
    class V21MigrationPrecision {

        @Test
        @DisplayName("ddl-auto=validate 통과 — V21 마이그레이션 적용 후 MacroIndicator precision=24 정합")
        void ddlValidatePasses_afterV21Migration() {
            // @SpringBootTest + ddl-auto=validate 가 통과하면 V21과 엔티티 precision이 정합함
            // 추가로 대형 원 정규화 값 저장 확인
            BigDecimal largeKrwValue = new BigDecimal("53500000000000.00000000"); // ~53.5조원
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(
                            "MKTFUND_V21_PRECISION_TEST",
                            LocalDate.of(2026, 6, 12),
                            largeKrwValue));

            MacroIndicator saved =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> "MKTFUND_V21_PRECISION_TEST".equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getValue()).isEqualByComparingTo(largeKrwValue);
        }

        @Test
        @DisplayName("예탁금 원 정규화 값(~10^14) 자릿수 오버플로우 없이 저장됨")
        void largeKrwNormalization_noOverflow() {
            // Arrange — 예탁금 535,000억원 × 10^8 = 5.35 × 10^13
            BigDecimal custDeposit = new BigDecimal("53500000000000"); // 53.5조원
            macroIndicatorRepository.insertIgnoreDuplicate(
                    buildIndicator(
                            "MKTFUND_CUST_DEPOSIT_V21", LocalDate.of(2026, 6, 13), custDeposit));

            // Act & Assert
            MacroIndicator saved =
                    macroIndicatorRepository.findAll().stream()
                            .filter(m -> "MKTFUND_CUST_DEPOSIT_V21".equals(m.getIndicatorCode()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getValue()).isEqualByComparingTo(custDeposit);
        }
    }
}
