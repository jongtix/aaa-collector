package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 투자자별 매매동향 리포지토리. */
public interface InvestorTrendRepository extends JpaRepository<InvestorTrend, Long> {

    /**
     * 투자자 매매동향 1건을 멱등 삽입한다 (REQ-BATCH2-021, -024).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} 는 기존 행을 변경하지 않는 no-op upsert 패턴이다. Unique Key {@code
     * uk_investor_trend (stock_id, trade_date)} 충돌 시 행 수가 증가하지 않으며 UPDATE도 발생하지 않는다 (TECHSPEC 4절).
     *
     * <p>엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다(긴 파라미터 목록 회피). 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합
     * 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티 (트랜지언트, stock·trade_date 등 매핑 필드 보유)
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO investor_trend
                        (stock_id, trade_date, foreign_net_qty, institution_net_qty, individual_net_qty,
                         foreign_net_value, institution_net_value, individual_net_value,
                         total_volume, total_trading_value, created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.foreignNetQty}, :#{#e.institutionNetQty}, :#{#e.individualNetQty},
                         :#{#e.foreignNetValue}, :#{#e.institutionNetValue}, :#{#e.individualNetValue},
                         :#{#e.totalVolume}, :#{#e.totalTradingValue}, NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") InvestorTrend e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(t) FROM InvestorTrend t WHERE t.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
