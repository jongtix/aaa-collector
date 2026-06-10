package com.aaa.collector.stock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 일봉 시세 리포지토리. */
public interface DailyOhlcvRepository extends JpaRepository<DailyOhlcv, Long> {

    /**
     * 일봉 1건을 멱등 삽입한다 (REQ-BATCH-031, -032).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} 는 기존 행을 변경하지 않는 no-op upsert 패턴이다. Unique Key {@code
     * uk_daily_ohlcv (stock_id, trade_date)} 충돌 시 행 수가 증가하지 않으며, UPDATE도 발생하지 않는다 (TECHSPEC 4절 시계열
     * 규칙).
     *
     * <p>네이티브 MySQL 쿼리 — H2 환경에서는 동작하지 않으므로 반드시 MySQL Testcontainer 기반 통합 테스트로 검증한다.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO daily_ohlcv
                        (stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, created_at, updated_at)
                    VALUES
                        (:stockId, :tradeDate, :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :tradingValue, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(
            @Param("stockId") Long stockId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("openPrice") BigDecimal openPrice,
            @Param("highPrice") BigDecimal highPrice,
            @Param("lowPrice") BigDecimal lowPrice,
            @Param("closePrice") BigDecimal closePrice,
            @Param("volume") long volume,
            @Param("tradingValue") long tradingValue);

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
