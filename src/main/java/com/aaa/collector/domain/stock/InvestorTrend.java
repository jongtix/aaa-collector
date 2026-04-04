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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 투자자별 매매동향. */
@Entity
@Table(
        name = "investor_trend",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_investor_trend",
                        columnNames = {"stock_id", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class InvestorTrend extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_investor_trend_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "foreign_net_qty")
    private final long foreignNetQty;

    @Column(name = "institution_net_qty")
    private final long institutionNetQty;

    @Column(name = "individual_net_qty")
    private final long individualNetQty;

    @Column(name = "foreign_net_value")
    private final long foreignNetValue;

    @Column(name = "institution_net_value")
    private final long institutionNetValue;

    @Column(name = "individual_net_value")
    private final long individualNetValue;

    @Column(name = "total_volume")
    private final long totalVolume;

    @Column(name = "total_trading_value")
    private final long totalTradingValue;

    @Builder
    private InvestorTrend(
            Stock stock,
            LocalDate tradeDate,
            long foreignNetQty,
            long institutionNetQty,
            long individualNetQty,
            long foreignNetValue,
            long institutionNetValue,
            long individualNetValue,
            long totalVolume,
            long totalTradingValue) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        this.foreignNetQty = foreignNetQty;
        this.institutionNetQty = institutionNetQty;
        this.individualNetQty = individualNetQty;
        this.foreignNetValue = foreignNetValue;
        this.institutionNetValue = institutionNetValue;
        this.individualNetValue = individualNetValue;
        this.totalVolume = totalVolume;
        this.totalTradingValue = totalTradingValue;
    }
}
