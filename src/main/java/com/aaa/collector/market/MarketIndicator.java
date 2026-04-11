package com.aaa.collector.market;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.market.enums.IndicatorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 시장 지표 (VIX, 환율 등). */
@Entity
@Table(
        name = "market_indicators",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_market_indicators",
                        columnNames = {"indicator_code", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class MarketIndicator extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "indicator_code", length = 20)
    private final IndicatorCode indicatorCode;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "open_value", precision = 20, scale = 4)
    private final BigDecimal openValue;

    @Column(name = "high_value", precision = 20, scale = 4)
    private final BigDecimal highValue;

    @Column(name = "low_value", precision = 20, scale = 4)
    private final BigDecimal lowValue;

    @Column(name = "close_value", precision = 20, scale = 4)
    private final BigDecimal closeValue;

    @Column(name = "source", length = 30)
    private final String source;

    @Builder
    private MarketIndicator(
            IndicatorCode indicatorCode,
            LocalDate tradeDate,
            BigDecimal openValue,
            BigDecimal highValue,
            BigDecimal lowValue,
            BigDecimal closeValue,
            String source) {
        super();
        this.indicatorCode = indicatorCode;
        this.tradeDate = tradeDate;
        this.openValue = openValue;
        this.highValue = highValue;
        this.lowValue = lowValue;
        this.closeValue = closeValue;
        this.source = source;
    }
}
