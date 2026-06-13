package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 국내 공매도 일별추이 리포지토리. */
public interface ShortSaleDomesticRepository extends JpaRepository<ShortSaleDomestic, Long> {

    /**
     * 공매도 일별추이 1건을 멱등 삽입한다 (REQ-BATCH2-021, -024).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code
     * uk_short_sale_domestic (stock_id, trade_date)} 충돌 시 행 수 미증가·UPDATE 미발생 (TECHSPEC 4절).
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
                    INSERT INTO short_sale_domestic
                        (stock_id, trade_date, short_sell_qty, short_sell_vol_rate, short_sell_amt,
                         short_sell_amt_rate, short_sell_acc_qty, short_sell_acc_qty_rate,
                         short_sell_acc_amt, short_sell_acc_amt_rate, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.shortSellQty}, :#{#e.shortSellVolRate}, :#{#e.shortSellAmt},
                         :#{#e.shortSellAmtRate}, :#{#e.shortSellAccQty}, :#{#e.shortSellAccQtyRate},
                         :#{#e.shortSellAccAmt}, :#{#e.shortSellAccAmtRate}, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") ShortSaleDomestic e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(s) FROM ShortSaleDomestic s WHERE s.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
