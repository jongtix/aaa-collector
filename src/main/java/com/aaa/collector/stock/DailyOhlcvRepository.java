package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 일봉 시세 리포지토리. */
public interface DailyOhlcvRepository extends JpaRepository<DailyOhlcv, Long> {

    /**
     * 시장 필터로 최신 적재 시각을 조회한다 (SPEC-OBSV-WARMSTART-001 warm-start용).
     *
     * <p>국내({@code domestic-daily}): KOSPI, KOSDAQ, KRX / 미국({@code overseas-daily}): NYSE, NASDAQ,
     * AMEX, US 로 분리 조회한다.
     *
     * @param markets 조회 대상 Market enum 컬렉션
     * @return MAX(createdAt) — 한 건도 없으면 {@link Optional#empty()}
     */
    @Query("SELECT MAX(o.createdAt) FROM DailyOhlcv o WHERE o.stock.market IN :markets")
    Optional<LocalDateTime> findMaxCreatedAtByMarketsIn(
            @Param("markets") Collection<Market> markets);

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 daily_ohlcv에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시 중복 충돌에서
    // SQL 1142 발생 (ADR-025, SPEC-COLLECTOR-OHLCV-001)
    /**
     * 일봉 1건을 멱등 삽입한다 (REQ-BATCH-031, -032, REQ-OHLCV1-001, -002, -020).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_daily_ohlcv (stock_id, trade_date)} 충돌 시 해당 행을
     * 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 SET 대상
     * 컬럼의 UPDATE 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command
     * denied})를 유발했다(ADR-025 §맥락 1).
     *
     * <p>{@code INSERT IGNORE}는 UPDATE 권한을 요구하지 않아 {@code collector} 최소 권한 (SELECT, INSERT)을
     * 유지한다(TECHSPEC §4 시계열 테이블 원칙).
     *
     * <p>네이티브 MySQL 쿼리 — H2 미동작, 반드시 MySQL Testcontainer 통합 테스트로 검증.
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO daily_ohlcv
                        (stock_id, trade_date, open_price, high_price, low_price, close_price, volume, trading_value, created_at, updated_at)
                    VALUES
                        (:stockId, :tradeDate, :openPrice, :highPrice, :lowPrice, :closePrice, :volume, :tradingValue, NOW(), NOW())
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

    /**
     * 불일치 탐지용 기존 행 일괄 조회 (REQ-OHLCV2-010 — 종목·날짜 배치 읽기).
     *
     * <p>한 종목의 응답 날짜 집합을 IN 절 단건 쿼리로 조회하여 N+1 read를 방지한다 (ADR-025 §한계 — 탐지 비용 최소화). 읽기 전용 — {@code
     * INSERT IGNORE} 멱등 쿼리(OHLCV-001)와 독립적으로 존재한다.
     *
     * <p>
     * <!-- @MX:NOTE: [AUTO] 불일치 탐지 배치 읽기 — N+1 방지용 IN 절 일괄 조회. -->
     * <!-- @MX:REASON: ADR-025 §한계 — 종목당 14일 규모로 비용 미미하나 단건 조회는 누적 비용 문제. -->
     */
    @Query(
            "SELECT o FROM DailyOhlcv o "
                    + "WHERE o.stock.id = :stockId AND o.tradeDate IN :tradeDates")
    List<DailyOhlcv> findByStockIdAndTradeDateIn(
            @Param("stockId") Long stockId, @Param("tradeDates") Collection<LocalDate> tradeDates);

    /**
     * 종목의 최초 거래일(MIN trade_date)을 조회한다 (REQ-STOCKMETA-013, SPEC-COLLECTOR-STOCKMETA-001).
     *
     * <p>상장일(listed_date)이 NULL인 종목의 상장일 근사치 추정에 사용된다. 거래 데이터가 없으면 {@link Optional#empty()}를 반환한다.
     */
    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Query("SELECT MIN(o.tradeDate) FROM DailyOhlcv o WHERE o.stock.id = :stockId")
    Optional<LocalDate> findMinTradeDateByStockId(@Param("stockId") Long stockId);
}
