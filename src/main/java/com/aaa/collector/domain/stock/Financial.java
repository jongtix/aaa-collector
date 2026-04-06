package com.aaa.collector.domain.stock;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.domain.stock.enums.PeriodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 재무제표 (KIS 국내주식재무비율). */
@Entity
@Table(
        name = "financials",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_financials",
                        columnNames = {"stock_id", "period_type", "period_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Financial extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_financials_stock"))
    private final Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", length = 10)
    private final PeriodType periodType;

    @Column(name = "period_date")
    private final LocalDate periodDate;

    @Column(name = "revenue_growth", precision = 12, scale = 4)
    private final BigDecimal revenueGrowth;

    @Column(name = "operating_profit_growth", precision = 12, scale = 4)
    private final BigDecimal operatingProfitGrowth;

    @Column(name = "net_income_growth", precision = 12, scale = 4)
    private final BigDecimal netIncomeGrowth;

    @Column(name = "roe", precision = 12, scale = 4)
    private final BigDecimal roe;

    @Column(name = "eps")
    private final Long eps;

    @Column(name = "sps")
    private final Long sps;

    @Column(name = "bps")
    private final Long bps;

    @Column(name = "retention_rate", precision = 12, scale = 4)
    private final BigDecimal retentionRate;

    @Column(name = "debt_ratio", precision = 12, scale = 4)
    private final BigDecimal debtRatio;

    @Builder
    private Financial(
            Stock stock,
            PeriodType periodType,
            LocalDate periodDate,
            BigDecimal revenueGrowth,
            BigDecimal operatingProfitGrowth,
            BigDecimal netIncomeGrowth,
            BigDecimal roe,
            Long eps,
            Long sps,
            Long bps,
            BigDecimal retentionRate,
            BigDecimal debtRatio) {
        super();
        this.stock = stock;
        this.periodType = periodType;
        this.periodDate = periodDate;
        this.revenueGrowth = revenueGrowth;
        this.operatingProfitGrowth = operatingProfitGrowth;
        this.netIncomeGrowth = netIncomeGrowth;
        this.roe = roe;
        this.eps = eps;
        this.sps = sps;
        this.bps = bps;
        this.retentionRate = retentionRate;
        this.debtRatio = debtRatio;
    }
}
