package com.aaa.collector.stock;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 일봉 시세 리포지토리. */
public interface DailyOhlcvRepository extends JpaRepository<DailyOhlcv, Long> {

    /**
     * Counts distinct trading days for a stock.
     *
     * <p>Used by EtfRepresentativeService to determine if a stock has >= 20 valid trading days
     * (REQ-ETFALG-006).
     */
    @Query("SELECT COUNT(o) FROM DailyOhlcv o WHERE o.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);

    /**
     * Calculates the Average Daily Trading Value (ADTV) for multiple stocks in a single query.
     *
     * <p>Returns pairs of [stockId, adtv] for use in the representative selection comparator.
     */
    @Query(
            "SELECT o.stock.id, AVG(o.tradingValue) FROM DailyOhlcv o "
                    + "WHERE o.stock.id IN :stockIds "
                    + "GROUP BY o.stock.id")
    List<Object[]> findAdtvByStockIds(@Param("stockIds") List<Long> stockIds);
}
