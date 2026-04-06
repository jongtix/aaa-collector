package com.aaa.collector.domain.stock;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 신용잔고 일별 추이. */
@Entity
@Table(
        name = "credit_balance",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_credit_balance",
                        columnNames = {"stock_id", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class CreditBalance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_credit_balance_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "loan_new_qty")
    private final long loanNewQty;

    @Column(name = "loan_repay_qty")
    private final long loanRepayQty;

    @Column(name = "loan_balance_qty")
    private final long loanBalanceQty;

    @Column(name = "loan_new_amt")
    private final long loanNewAmt;

    @Column(name = "loan_repay_amt")
    private final long loanRepayAmt;

    @Column(name = "loan_balance_amt")
    private final long loanBalanceAmt;

    @Column(name = "loan_balance_rate", precision = 7, scale = 4)
    private final BigDecimal loanBalanceRate;

    @Column(name = "loan_supply_rate", precision = 7, scale = 4)
    private final BigDecimal loanSupplyRate;

    @Column(name = "lend_new_qty")
    private final long lendNewQty;

    @Column(name = "lend_repay_qty")
    private final long lendRepayQty;

    @Column(name = "lend_balance_qty")
    private final long lendBalanceQty;

    @Column(name = "lend_new_amt")
    private final long lendNewAmt;

    @Column(name = "lend_repay_amt")
    private final long lendRepayAmt;

    @Column(name = "lend_balance_amt")
    private final long lendBalanceAmt;

    @Column(name = "lend_balance_rate", precision = 7, scale = 4)
    private final BigDecimal lendBalanceRate;

    @Column(name = "lend_supply_rate", precision = 7, scale = 4)
    private final BigDecimal lendSupplyRate;

    @Builder
    private CreditBalance(
            Stock stock,
            LocalDate tradeDate,
            long loanNewQty,
            long loanRepayQty,
            long loanBalanceQty,
            long loanNewAmt,
            long loanRepayAmt,
            long loanBalanceAmt,
            BigDecimal loanBalanceRate,
            BigDecimal loanSupplyRate,
            long lendNewQty,
            long lendRepayQty,
            long lendBalanceQty,
            long lendNewAmt,
            long lendRepayAmt,
            long lendBalanceAmt,
            BigDecimal lendBalanceRate,
            BigDecimal lendSupplyRate) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        this.loanNewQty = loanNewQty;
        this.loanRepayQty = loanRepayQty;
        this.loanBalanceQty = loanBalanceQty;
        this.loanNewAmt = loanNewAmt;
        this.loanRepayAmt = loanRepayAmt;
        this.loanBalanceAmt = loanBalanceAmt;
        this.loanBalanceRate = loanBalanceRate;
        this.loanSupplyRate = loanSupplyRate;
        this.lendNewQty = lendNewQty;
        this.lendRepayQty = lendRepayQty;
        this.lendBalanceQty = lendBalanceQty;
        this.lendNewAmt = lendNewAmt;
        this.lendRepayAmt = lendRepayAmt;
        this.lendBalanceAmt = lendBalanceAmt;
        this.lendBalanceRate = lendBalanceRate;
        this.lendSupplyRate = lendSupplyRate;
    }
}
