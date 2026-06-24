package com.aaa.collector.stock.etf;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** ETF 대표 종목 이력 리포지토리. Append-only — 조회/INSERT만 허용. */
public interface EtfRepresentativeHistoryRepository
        extends JpaRepository<EtfRepresentativeHistory, Long> {

    /** Returns the most recent representative history entry for the given group_key. */
    @Query(
            "SELECT h FROM EtfRepresentativeHistory h "
                    + "WHERE h.groupKey = :groupKey "
                    + "ORDER BY h.effectiveFrom DESC "
                    + "LIMIT 1")
    Optional<EtfRepresentativeHistory> findLatestByGroupKey(@Param("groupKey") String groupKey);

    /**
     * 가장 최근 ETF 대표종목 교체 시각을 조회한다 (SPEC-OBSV-WARMSTART-001 warm-start용).
     *
     * <p>EtfRepresentativeHistory는 BaseEntity를 상속하지 않으므로 {@code effectiveFrom}으로 대체한다.
     *
     * @return MAX(effectiveFrom) — 한 건도 없으면 {@link Optional#empty()}
     */
    @Query("SELECT MAX(h.effectiveFrom) FROM EtfRepresentativeHistory h")
    Optional<LocalDateTime> findMaxEffectiveFrom();
}
