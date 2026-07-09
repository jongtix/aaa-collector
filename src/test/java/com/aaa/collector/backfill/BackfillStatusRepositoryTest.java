package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M2-T1 격리 분류 — 싱글턴 공유 제외(전용 컨테이너). M2-T3(REQ-DBGRANT3-013)에서 {@code @BeforeEach} 정리를 {@link
 * RootFixtureCleaner#deleteAllBackfillStatus(String)} root 커넥션으로 재배선했다 — 테스트 대상 코드 경로(리포지토리 호출)는 앱
 * datasource를 그대로 사용하며, M2-T4에서 datasource가 {@code collector} 계정(DELETE 권한 없음)으로 전환된 뒤에도 이 정리는 계속
 * 동작한다. 격리 전략 재설계(전체 삭제 대신 스코프 한정 정리)는 본 SPEC 범위 밖.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("BackfillStatusRepository 통합 테스트 (V24 backfill_status DDL 매핑)")
@Tag("integration")
class BackfillStatusRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private BackfillStatusRepository backfillStatusRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanUp() throws SQLException {
        // root 커넥션으로 정리(M2-T3) — 테스트 간 행 격리를 위해 매 테스트 전 비운다.
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
    }

    private static BackfillStatus.BackfillStatusBuilder row(String targetCode, String dataTable) {
        return BackfillStatus.builder()
                .targetType("STOCK")
                .targetCode(targetCode)
                .dataTable(dataTable)
                .status(BackfillStatusType.PENDING);
    }

    @Nested
    @DisplayName("UNIQUE 제약 uk_backfill_status (target_type, target_code, data_table)")
    class UniqueConstraint {

        @Test
        @DisplayName("동일 (target_type, target_code, data_table) 중복 저장 — 제약 위반 예외")
        void duplicateKey_throwsConstraintViolation() {
            // Arrange
            backfillStatusRepository.saveAndFlush(row("005930", "daily_ohlcv").build());

            // Act & Assert
            assertThatThrownBy(
                            () ->
                                    backfillStatusRepository.saveAndFlush(
                                            row("005930", "daily_ohlcv").build()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("동일 종목·다른 data_table — 독립 행으로 저장됨")
        void sameTargetDifferentTable_storedIndependently() {
            backfillStatusRepository.saveAndFlush(row("005930", "daily_ohlcv").build());
            backfillStatusRepository.saveAndFlush(row("005930", "investor_trend").build());

            assertThat(backfillStatusRepository.count()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("DDL DEFAULT 값 (Flyway 단독 관리)")
    class DefaultValues {

        @Test
        @DisplayName("status/stale_count/attempt_count 미지정 INSERT — DB DEFAULT 적용 (PENDING/0/0)")
        void omittedColumns_applyDdlDefaults() {
            // Arrange — status/stale_count/attempt_count/last_collected_date 컬럼을 생략한 네이티브 INSERT
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(
                    "INSERT INTO backfill_status "
                            + "(target_type, target_code, data_table, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    "STOCK",
                    "000660",
                    "credit_balance",
                    now,
                    now);

            // Act
            BackfillStatus saved = backfillStatusRepository.findAll().getFirst();

            // Assert
            assertThat(saved.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(saved.getStaleCount()).isZero();
            assertThat(saved.getAttemptCount()).isZero();
        }

        @Test
        @DisplayName("last_collected_date 미지정 — NULL 허용")
        void lastCollectedDate_isNullable() {
            LocalDateTime now = LocalDateTime.now();
            jdbcTemplate.update(
                    "INSERT INTO backfill_status "
                            + "(target_type, target_code, data_table, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    "STOCK",
                    "035420",
                    "short_sale_domestic",
                    now,
                    now);

            BackfillStatus saved = backfillStatusRepository.findAll().getFirst();

            assertThat(saved.getLastCollectedDate()).isNull();
            assertThat(saved.getLastRowCount()).isNull();
            assertThat(saved.getLastError()).isNull();
        }
    }

    @Nested
    @DisplayName("인덱스 idx_backfill_status_status")
    class StatusIndex {

        @Test
        @DisplayName("status 컬럼 인덱스가 존재한다")
        void statusIndex_exists() {
            Integer indexCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.statistics "
                                    + "WHERE table_schema = DATABASE() "
                                    + "AND table_name = 'backfill_status' "
                                    + "AND index_name = 'idx_backfill_status_status'",
                            Integer.class);

            assertThat(indexCount).isNotNull().isPositive();
        }
    }

    @Nested
    @DisplayName(
            "updatedAt Auditing 발화 — JPA dirty-check 경로 (REQ-UPDATEDAT-001, REQ-UPDATEDAT-007)")
    class UpdatedAtAuditing {

        @Test
        @DisplayName("advance() 호출 후 updatedAt이 created_at과 달라진다 (REQ-007 AC-1)")
        void advance_updatesUpdatedAt() throws InterruptedException {
            // Arrange
            BackfillStatus seeded =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("STOCK")
                                    .targetCode("005930")
                                    .dataTable("daily_ohlcv")
                                    .status(BackfillStatusType.PENDING)
                                    .build());
            // DB에서 다시 로드해 seededUpdatedAt 획득 (DATETIME = 초 단위 정밀도)
            LocalDateTime seededUpdatedAt =
                    backfillStatusRepository.findById(seeded.getId()).orElseThrow().getUpdatedAt();

            // DATETIME 컬럼은 초 단위 정밀도 — 갱신 감지에 1초 이상 경과 필요
            Thread.sleep(1100);

            // Act — 프로덕션 @Transactional persistWindow() 경로 재현: MANAGED 엔티티 dirty-check
            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(seeded.getId()).orElseThrow();
                        managed.advance(
                                BackfillStatusType.IN_PROGRESS, LocalDate.of(2025, 1, 1), 0, 10);
                        // save() 없음 — MANAGED 상태에서 트랜잭션 커밋 시 dirty-check → @LastModifiedDate 발화
                    });

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(seeded.getId()).orElseThrow();
            assertThat(updated.getUpdatedAt()).isAfter(seededUpdatedAt);
        }

        @Test
        @DisplayName("fail() 호출 후 updatedAt이 seed 시각과 달라진다 (REQ-007 AC-2)")
        void fail_updatesUpdatedAt() throws InterruptedException {
            // Arrange
            BackfillStatus seeded =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("STOCK")
                                    .targetCode("005930")
                                    .dataTable("investor_trend")
                                    .status(BackfillStatusType.PENDING)
                                    .build());
            // DB에서 다시 로드해 seededUpdatedAt 획득 (DATETIME = 초 단위 정밀도)
            LocalDateTime seededUpdatedAt =
                    backfillStatusRepository.findById(seeded.getId()).orElseThrow().getUpdatedAt();

            Thread.sleep(1100);

            // Act — 프로덕션 TransactionTemplate 실패 기록 경로 재현: MANAGED 엔티티 dirty-check
            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(seeded.getId()).orElseThrow();
                        managed.fail(BackfillStatusType.FAILED, "test error");
                        // save() 없음 — MANAGED 상태에서 트랜잭션 커밋 시 dirty-check → @LastModifiedDate 발화
                    });

            // Assert
            BackfillStatus updated =
                    backfillStatusRepository.findById(seeded.getId()).orElseThrow();
            assertThat(updated.getUpdatedAt()).isAfter(seededUpdatedAt);
        }
    }

    @Nested
    @DisplayName("verified_at 검증 마커 + 신뢰 기준선 (SPEC-COLLECTOR-BACKFILL-010 AC-5, AC-6)")
    class VerifiedBaseline {

        @Test
        @DisplayName("V35 verified_at 컬럼이 datetime·nullable로 존재한다 (AC-5)")
        void verifiedAtColumn_existsAsNullableDatetime() {
            // Arrange & Act
            String dataType =
                    jdbcTemplate.queryForObject(
                            "SELECT data_type FROM information_schema.columns "
                                    + "WHERE table_schema = DATABASE() "
                                    + "AND table_name = 'backfill_status' "
                                    + "AND column_name = 'verified_at'",
                            String.class);
            String nullable =
                    jdbcTemplate.queryForObject(
                            "SELECT is_nullable FROM information_schema.columns "
                                    + "WHERE table_schema = DATABASE() "
                                    + "AND table_name = 'backfill_status' "
                                    + "AND column_name = 'verified_at'",
                            String.class);

            // Assert
            assertThat(dataType).isEqualTo("datetime");
            assertThat(nullable).isEqualTo("YES");
        }

        @Test
        @DisplayName("기존 COMPLETED 행은 verified_at IS NULL(소급 검증 없음) (AC-5)")
        void existingCompleted_hasNullVerifiedAt() {
            BackfillStatus completed =
                    backfillStatusRepository.saveAndFlush(
                            row("005930", "daily_ohlcv")
                                    .status(BackfillStatusType.COMPLETED)
                                    .lastCollectedDate(LocalDate.of(2019, 12, 17))
                                    .build());

            BackfillStatus found =
                    backfillStatusRepository.findById(completed.getId()).orElseThrow();

            assertThat(found.getVerifiedAt()).isNull();
        }

        @Test
        @DisplayName("markVerified 후 verified_at이 영속된다 (dirty-check)")
        void markVerified_persists() {
            BackfillStatus seeded =
                    backfillStatusRepository.saveAndFlush(
                            row("AAPL", "daily_ohlcv")
                                    .status(BackfillStatusType.COMPLETED)
                                    .lastCollectedDate(LocalDate.of(2007, 8, 20))
                                    .build());
            LocalDateTime verifiedAt = LocalDateTime.of(2026, 7, 9, 10, 0);

            transactionTemplate.executeWithoutResult(
                    tx -> {
                        BackfillStatus managed =
                                backfillStatusRepository.findById(seeded.getId()).orElseThrow();
                        managed.markVerified(verifiedAt);
                    });

            BackfillStatus found = backfillStatusRepository.findById(seeded.getId()).orElseThrow();
            assertThat(found.getVerifiedAt()).isEqualTo(verifiedAt);
        }

        @Test
        @DisplayName("findVerifiedBaseline: 검증된 완료만 기준선, 미검증 COMPLETED는 승격 안 됨 (AC-6)")
        void findVerifiedBaseline_onlyVerifiedCompletion() {
            // Arrange — (a) 검증된 완료, (b) 미검증 완료
            LocalDate verifiedDate = LocalDate.of(2007, 8, 20);
            BackfillStatus verified =
                    row("VER", "daily_ohlcv")
                            .status(BackfillStatusType.COMPLETED)
                            .lastCollectedDate(verifiedDate)
                            .verifiedAt(LocalDateTime.of(2026, 7, 9, 10, 0))
                            .build();
            backfillStatusRepository.saveAndFlush(verified);
            backfillStatusRepository.saveAndFlush(
                    row("UNVER", "daily_ohlcv")
                            .status(BackfillStatusType.COMPLETED)
                            .lastCollectedDate(LocalDate.of(2019, 12, 17))
                            .build());

            // Act
            var verifiedBaseline =
                    backfillStatusRepository.findVerifiedBaseline("STOCK", "VER", "daily_ohlcv");
            var unverifiedBaseline =
                    backfillStatusRepository.findVerifiedBaseline("STOCK", "UNVER", "daily_ohlcv");

            // Assert
            assertThat(verifiedBaseline).contains(verifiedDate);
            assertThat(unverifiedBaseline).isEmpty();
        }
    }

    @Nested
    @DisplayName("엔티티 매핑 round-trip")
    class EntityMapping {

        private BackfillStatus fullRow() {
            return BackfillStatus.builder()
                    .targetType("STOCK")
                    .targetCode("AAPL")
                    .dataTable("daily_ohlcv")
                    .status(BackfillStatusType.IN_PROGRESS)
                    .lastCollectedDate(LocalDate.of(2024, 6, 7))
                    .staleCount(2)
                    .lastRowCount(61)
                    .attemptCount(5)
                    .lastError("timeout")
                    .build();
        }

        @Test
        @DisplayName("모든 비즈니스 필드 round-trip 저장·조회 일치")
        void allBusinessFields_roundTrip() {
            BackfillStatus persisted = backfillStatusRepository.saveAndFlush(fullRow());

            BackfillStatus found =
                    backfillStatusRepository.findById(persisted.getId()).orElseThrow();

            // id/auditing 필드는 별도 테스트(auditing_populated)에서 검증 — 여기선 비즈니스 필드 일치만 본다.
            assertThat(found)
                    .usingRecursiveComparison()
                    .ignoringFields("id", "createdAt", "updatedAt")
                    .isEqualTo(fullRow());
        }

        @Test
        @DisplayName("BaseEntity 감사 필드가 채워진다")
        void auditing_populated() {
            BackfillStatus persisted = backfillStatusRepository.saveAndFlush(fullRow());

            BackfillStatus found =
                    backfillStatusRepository.findById(persisted.getId()).orElseThrow();

            assertThat(found.getCreatedAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }
}
