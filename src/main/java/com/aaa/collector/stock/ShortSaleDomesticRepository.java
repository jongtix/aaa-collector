package com.aaa.collector.stock;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 국내 공매도 일별추이 리포지토리. */
public interface ShortSaleDomesticRepository extends JpaRepository<ShortSaleDomestic, Long> {

    // @MX:WARN: [AUTO] 권한 민감 네이티브 SQL — 반드시 INSERT IGNORE 유지 (ON DUPLICATE KEY UPDATE 금지)
    // @MX:REASON: [AUTO] collector는 short_sale_domestic에 UPDATE 권한이 없어 ON DUPLICATE KEY UPDATE 사용 시
    // 중복 충돌에서
    // SQL 1142 발생 (ADR-026, SPEC-COLLECTOR-DBGRANT-002)
    /**
     * 공매도 일별추이 1건을 멱등 삽입한다 (REQ-BATCH2-021, -024).
     *
     * <p>{@code INSERT IGNORE}는 Unique Key {@code uk_short_sale_domestic (stock_id, trade_date)} 충돌
     * 시 해당 행을 무시하여 행 수가 증가하지 않으며 UPDATE를 발생시키지 않는다.
     *
     * <p>기존 {@code ON DUPLICATE KEY UPDATE id = id}는 no-op이라도 MySQL이 중복 충돌 시 UPDATE 경로를 밟아 UPDATE
     * 권한을 검사하므로, UPDATE 권한이 없는 {@code collector} 사용자에게 SQL 1142({@code UPDATE command denied to
     * user 'collector'@'%' for table 'short_sale_domestic'})를 유발한다(ADR-026 Tier-1, ADR-025 §맥락 1).
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT IGNORE INTO short_sale_domestic
                        (stock_id, trade_date, short_sell_qty, short_sell_vol_rate, short_sell_amt,
                         short_sell_amt_rate, short_sell_acc_qty, short_sell_acc_qty_rate,
                         short_sell_acc_amt, short_sell_acc_amt_rate, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.shortSellQty}, :#{#e.shortSellVolRate}, :#{#e.shortSellAmt},
                         :#{#e.shortSellAmtRate}, :#{#e.shortSellAccQty}, :#{#e.shortSellAccQtyRate},
                         :#{#e.shortSellAccAmt}, :#{#e.shortSellAccAmtRate}, NOW(), NOW())
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") ShortSaleDomestic e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(s) FROM ShortSaleDomestic s WHERE s.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);

    /** 최신 적재 시각 조회 (SPEC-OBSV-WARMSTART-001 warm-start용). */
    @Query("SELECT MAX(s.createdAt) FROM ShortSaleDomestic s")
    Optional<LocalDateTime> findMaxCreatedAt();
}
