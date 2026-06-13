package com.aaa.collector.stock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 신용잔고 일별추이 리포지토리. */
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

    /**
     * 신용잔고 일별추이 1건을 멱등 삽입한다 (REQ-BATCH2-021, -024).
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id=id} no-op upsert. Unique Key {@code uk_credit_balance
     * (stock_id, trade_date)} 충돌 시 행 수 미증가·UPDATE 미발생 (TECHSPEC 4절).
     *
     * <p>{@code trade_date}에는 {@code deal_date}(매매일자)를 매핑한 엔티티를 전달한다 — {@code stlm_date}
     * 아님(REQ-BATCH2-052). 엔티티 필드를 SpEL로 참조하여 단일 파라미터로 전달한다. 네이티브 MySQL 쿼리 — MySQL Testcontainer 통합
     * 테스트로 검증한다.
     *
     * @param e 저장 대상 엔티티
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO credit_balance
                        (stock_id, trade_date, loan_new_qty, loan_repay_qty, loan_balance_qty,
                         loan_new_amt, loan_repay_amt, loan_balance_amt, loan_balance_rate, loan_supply_rate,
                         lend_new_qty, lend_repay_qty, lend_balance_qty,
                         lend_new_amt, lend_repay_amt, lend_balance_amt, lend_balance_rate, lend_supply_rate,
                         created_at, updated_at)
                    VALUES
                        (:#{#e.stock.id}, :#{#e.tradeDate}, :#{#e.loanNewQty}, :#{#e.loanRepayQty}, :#{#e.loanBalanceQty},
                         :#{#e.loanNewAmt}, :#{#e.loanRepayAmt}, :#{#e.loanBalanceAmt}, :#{#e.loanBalanceRate}, :#{#e.loanSupplyRate},
                         :#{#e.lendNewQty}, :#{#e.lendRepayQty}, :#{#e.lendBalanceQty},
                         :#{#e.lendNewAmt}, :#{#e.lendRepayAmt}, :#{#e.lendBalanceAmt}, :#{#e.lendBalanceRate}, :#{#e.lendSupplyRate},
                         NOW(), NOW())
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true)
    void insertIgnoreDuplicate(@Param("e") CreditBalance e);

    /** 종목별 저장 행 수 (멱등성 검증용). */
    @Query("SELECT COUNT(c) FROM CreditBalance c WHERE c.stock.id = :stockId")
    long countByStockId(@Param("stockId") Long stockId);
}
