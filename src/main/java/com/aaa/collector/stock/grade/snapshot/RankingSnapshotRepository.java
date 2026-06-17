package com.aaa.collector.stock.grade.snapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 7일 초과 행 prune — 쓰기 성공 후 호출 (M3).
     *
     * @param market 시장 구분
     * @param snapshotDateBefore 이 날짜 미만 행 삭제
     */
    @Modifying
    @Query(
            "DELETE FROM RankingSnapshot s WHERE s.market = :market "
                    + "AND s.snapshotDate < :snapshotDateBefore")
    void deleteByMarketAndSnapshotDateBefore(
            @Param("market") String market,
            @Param("snapshotDateBefore") LocalDate snapshotDateBefore);

    /**
     * 동일 cycle 재실행 멱등 처리 — 기존 행 삭제 (C2).
     *
     * @param market 시장 구분
     * @param snapshotDate 스냅샷 날짜
     */
    @Modifying
    @Query(
            "DELETE FROM RankingSnapshot s WHERE s.market = :market "
                    + "AND s.snapshotDate = :snapshotDate")
    void deleteByMarketAndSnapshotDate(
            @Param("market") String market, @Param("snapshotDate") LocalDate snapshotDate);
}
