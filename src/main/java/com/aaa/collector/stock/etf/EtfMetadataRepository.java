package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.enums.AssetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** ETF 메타데이터 리포지토리. */
public interface EtfMetadataRepository extends JpaRepository<EtfMetadata, Long> {

    Optional<EtfMetadata> findByStockId(Long stockId);

    /** Fetches all ETF metadata with stocks eagerly loaded to avoid N+1 in recalculate(). */
    @Query("SELECT m FROM EtfMetadata m JOIN FETCH m.stock")
    List<EtfMetadata> findAllWithStock();

    /**
     * Returns stock IDs of ETF stocks that do NOT have an etf_metadata row.
     *
     * <p>Used by EtfRepresentativeService to emit warn logs for ETF stocks missing metadata
     * (REQ-ETFALG-007).
     */
    @Query(
            "SELECT s.id FROM Stock s WHERE s.assetType = :etfType "
                    + "AND NOT EXISTS (SELECT 1 FROM EtfMetadata m WHERE m.stock = s)")
    List<Long> findStockIdsWithoutMetadata(@Param("etfType") AssetType etfType);
}
