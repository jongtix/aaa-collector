package com.aaa.collector.stock.grade.snapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** ranking_snapshots 테이블 저장소. */
public interface RankingSnapshotRepository extends JpaRepository<RankingSnapshot, Long> {

    /**
     * 시장+날짜로 스냅샷 조회.
     *
     * @param market 시장 구분
     * @param snapshotDate 스냅샷 날짜
     * @return 해당 날짜 스냅샷 목록
     */
    List<RankingSnapshot> findByMarketAndSnapshotDate(String market, LocalDate snapshotDate);

    /**
     * 시장+날짜 행 수 조회.
     *
     * @param market 시장 구분
     * @param snapshotDate 스냅샷 날짜
     * @return 행 수
     */
    long countByMarketAndSnapshotDate(String market, LocalDate snapshotDate);

    /**
     * 최신 snapshot_date 조회.
     *
     * @param market 시장 구분
     * @return 최신 날짜. 없으면 empty.
     */
    @Query("SELECT MAX(s.snapshotDate) FROM RankingSnapshot s WHERE s.market = :market")
    Optional<LocalDate> findLatestSnapshotDate(@Param("market") String market);

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 ranking_snapshots에 UPDATE/DELETE 권한이 없어 ON DUPLICATE KEY UPDATE
    // 사용 시
    // 중복 충돌에서 SQL 1142 발생; DELETE도 SQL 1142 — ADR-026 Tier-1, SPEC-COLLECTOR-DBGRANT-002
    /**
     * 순위 스냅샷 1건을 멱등 삽입한다 (ADR-026 Tier-1).
     *
     * <p>{@code INSERT IGNORE}는 UNIQUE(market, snapshot_date, symbol) 충돌 시 해당 행을 무시하여 행 수가 증가하지 않으며
     * UPDATE를 발생시키지 않는다.
     *
     * <p>{@code collector} 사용자는 {@code ranking_snapshots}에 UPDATE/DELETE 권한이 없다(ADR-026 Tier-1).
     * {@code ON DUPLICATE KEY UPDATE}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 SQL 1142를 유발한다. {@code
     * DELETE}도 동일하게 SQL 1142를 유발한다(aaa-infra issue #20).
     *
     * <p>멱등성: 동일 (market, snapshot_date, symbol)로 재호출해도 예외 없이 무시된다 — 수동 재실행 시 기존 행과의 union을 보장한다.
     *
     * <p>네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로 검증.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO ranking_snapshots
                        (market, snapshot_date, symbol, rank_value, rank_position, captured_at)
                    VALUES
                        (:market, :snapshotDate, :symbol, :rankValue, :rankPosition, :capturedAt)
                    """,
            nativeQuery = true)
    void insertIgnore(
            @Param("market") String market,
            @Param("snapshotDate") LocalDate snapshotDate,
            @Param("symbol") String symbol,
            @Param("rankValue") double rankValue,
            @Param("rankPosition") int rankPosition,
            @Param("capturedAt") Instant capturedAt);
}
