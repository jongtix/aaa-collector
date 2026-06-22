package com.aaa.collector.stock.grade.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
 * RankingSnapshotRepository INSERT IGNORE 멱등성 통합 검증 (Testcontainers MySQL).
 *
 * <p>H2 미사용 — INSERT IGNORE 시맨틱은 MySQL에서만 보장됨. {@link
 * com.aaa.collector.macro.MacroIndicatorRepositoryTest} 패턴 답습.
 *
 * <p>ADR-026 Tier-1: collector는 ranking_snapshots에 UPDATE/DELETE 권한 없음. INSERT IGNORE만 허용.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("RankingSnapshotRepository 통합 테스트 — INSERT IGNORE 멱등성")
class RankingSnapshotRepositoryIT {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private RankingSnapshotRepository rankingSnapshotRepository;

    private static final Instant CAPTURED = Instant.parse("2024-06-16T19:00:00Z");
    private static final String MARKET_KRX = "KRX";
    private static final String MARKET_US = "US";

    // ────────────────────────────────────────────────────────────────────
    // (a) 신규 행 삽입
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(a) 신규 행 삽입")
    class NewRowInsertion {

        @Test
        @DisplayName("insertIgnore — 새로운 (market, snapshot_date, symbol) 행 1건 저장")
        void newRowPersistsOne() {
            rankingSnapshotRepository.insertIgnore(
                    MARKET_KRX, LocalDate.of(2024, 6, 17), "005930", 100.0, 1, CAPTURED);

            assertThat(
                            rankingSnapshotRepository.countByMarketAndSnapshotDate(
                                    MARKET_KRX, LocalDate.of(2024, 6, 17)))
                    .isEqualTo(1L);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // (b) 중복 삽입 — 예외 없음, 행 수 불변
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(b) 중복 삽입 — 예외 없음, 행 수 불변")
    class DuplicateInsertion {

        @Test
        @DisplayName("동일 (market, snapshot_date, symbol) 재호출 — 예외 없음, 행 수 1 유지")
        void duplicateNoExceptionRowCountStaysOne() {
            // Arrange
            LocalDate date = LocalDate.of(2024, 6, 18);
            rankingSnapshotRepository.insertIgnore(MARKET_KRX, date, "000660", 80.0, 2, CAPTURED);

            // Act — 동일 unique key로 재삽입 (rank_value 다름)
            rankingSnapshotRepository.insertIgnore(MARKET_KRX, date, "000660", 999.0, 2, CAPTURED);

            // Assert — 행 수 불변, UPDATE 미발생 (INSERT IGNORE 보장)
            assertThat(rankingSnapshotRepository.countByMarketAndSnapshotDate(MARKET_KRX, date))
                    .isEqualTo(1L);

            // 원본 rank_value 유지 확인
            RankingSnapshot saved =
                    rankingSnapshotRepository
                            .findByMarketAndSnapshotDate(MARKET_KRX, date)
                            .getFirst();
            assertThat(saved.getRankValue()).isEqualTo(80.0);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // (c) 다른 symbol → 각각 독립 삽입 (union 시맨틱)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("(c) 다른 symbol → 각각 독립 삽입 (union 시맨틱)")
    class AppendSemantics {

        @Test
        @DisplayName("동일 (market, snapshot_date), 다른 symbol 두 건 — 모두 저장됨")
        void differentSymbolsSameMarketDateBothPersist() {
            LocalDate date = LocalDate.of(2024, 6, 19);
            rankingSnapshotRepository.insertIgnore(MARKET_KRX, date, "005930", 100.0, 1, CAPTURED);
            rankingSnapshotRepository.insertIgnore(MARKET_KRX, date, "000660", 80.0, 2, CAPTURED);

            assertThat(rankingSnapshotRepository.countByMarketAndSnapshotDate(MARKET_KRX, date))
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("KRX·US 동일 날짜 동일 symbol — 시장별 독립 행 보장")
        void sameSymbolDifferentMarketsIndependentRows() {
            LocalDate date = LocalDate.of(2024, 6, 20);
            rankingSnapshotRepository.insertIgnore(MARKET_KRX, date, "AAPL", 50.0, 1, CAPTURED);
            rankingSnapshotRepository.insertIgnore(MARKET_US, date, "AAPL", 60.0, 1, CAPTURED);

            assertThat(rankingSnapshotRepository.countByMarketAndSnapshotDate(MARKET_KRX, date))
                    .isEqualTo(1L);
            assertThat(rankingSnapshotRepository.countByMarketAndSnapshotDate(MARKET_US, date))
                    .isEqualTo(1L);
        }
    }
}
