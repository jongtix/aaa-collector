package com.aaa.collector.stock;

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

/** 국내 공매도 일별 추이. */
@Entity
@Table(
        name = "short_sale_domestic",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_short_sale_domestic",
                        columnNames = {"stock_id", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class ShortSaleDomestic extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_short_sale_domestic_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "short_sell_qty")
    private final long shortSellQty;

    @Column(name = "short_sell_vol_rate", precision = 7, scale = 4)
    private final BigDecimal shortSellVolRate;

    @Column(name = "short_sell_amt")
    private final long shortSellAmt;

    @Column(name = "short_sell_amt_rate", precision = 7, scale = 4)
    private final BigDecimal shortSellAmtRate;

    @Column(name = "short_sell_acc_qty")
    private final long shortSellAccQty;

    @Column(name = "short_sell_acc_qty_rate", precision = 7, scale = 4)
    private final BigDecimal shortSellAccQtyRate;

    @Column(name = "short_sell_acc_amt")
    private final long shortSellAccAmt;

    @Column(name = "short_sell_acc_amt_rate", precision = 7, scale = 4)
    private final BigDecimal shortSellAccAmtRate;

    @Builder
    private ShortSaleDomestic(
            Stock stock,
            LocalDate tradeDate,
            long shortSellQty,
            BigDecimal shortSellVolRate,
            long shortSellAmt,
            BigDecimal shortSellAmtRate,
            long shortSellAccQty,
            BigDecimal shortSellAccQtyRate,
            long shortSellAccAmt,
            BigDecimal shortSellAccAmtRate) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        this.shortSellQty = shortSellQty;
        this.shortSellVolRate = shortSellVolRate;
        this.shortSellAmt = shortSellAmt;
        this.shortSellAmtRate = shortSellAmtRate;
        this.shortSellAccQty = shortSellAccQty;
        this.shortSellAccQtyRate = shortSellAccQtyRate;
        this.shortSellAccAmt = shortSellAccAmt;
        this.shortSellAccAmtRate = shortSellAccAmtRate;
    }
}
