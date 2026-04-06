package com.aaa.collector.domain.futures;

import com.aaa.collector.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 해외선물 일봉 (ES, NQ, CL/WTI, VX). */
@Entity
@Table(
        name = "futures_daily",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_futures_daily",
                        columnNames = {"series_code", "contract_code", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class FuturesDaily extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_code", length = 10)
    private final String seriesCode;

    @Column(name = "contract_code", length = 20)
    private final String contractCode;

    @Column(name = "exchange_code", length = 10)
    private final String exchangeCode;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "open", precision = 18, scale = 6)
    private final BigDecimal open;

    @Column(name = "high", precision = 18, scale = 6)
    private final BigDecimal high;

    @Column(name = "low", precision = 18, scale = 6)
    private final BigDecimal low;

    @Column(name = "close", precision = 18, scale = 6)
    private final BigDecimal close;

    @Column(name = "volume")
    private final Long volume;

    @Column(name = "open_interest")
    private final Long openInterest;

    @Column(name = "is_continuous")
    private final boolean isContinuous;

    @Builder
    private FuturesDaily(
            String seriesCode,
            String contractCode,
            String exchangeCode,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume,
            Long openInterest,
            boolean isContinuous) {
        super();
        this.seriesCode = seriesCode;
        this.contractCode = contractCode;
        this.exchangeCode = exchangeCode;
        this.tradeDate = tradeDate;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.openInterest = openInterest;
        this.isContinuous = isContinuous;
    }
}
