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

/** 일봉 시세. */
@Entity
@Table(
        name = "daily_ohlcv",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_daily_ohlcv",
                        columnNames = {"stock_id", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class DailyOhlcv extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_daily_ohlcv_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "open_price", precision = 18, scale = 4)
    private final BigDecimal openPrice;

    @Column(name = "high_price", precision = 18, scale = 4)
    private final BigDecimal highPrice;

    @Column(name = "low_price", precision = 18, scale = 4)
    private final BigDecimal lowPrice;

    @Column(name = "close_price", precision = 18, scale = 4)
    private final BigDecimal closePrice;

    @Column(name = "volume")
    private final long volume;

    @Column(name = "trading_value")
    private final long tradingValue;

    @Builder
    private DailyOhlcv(
            Stock stock,
            LocalDate tradeDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            long volume,
            long tradingValue) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
        this.tradingValue = tradingValue;
    }
}
