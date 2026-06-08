package com.aaa.collector.stock.etf;

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
}
