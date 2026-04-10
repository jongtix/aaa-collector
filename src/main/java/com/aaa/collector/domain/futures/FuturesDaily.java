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

/**
 * 해외선물 일봉 (ES, NQ, CL/WTI, VX).
 *
 * <p>KIS API는 개별 월물(ESM24, ESZ24 등) 데이터만 제공하며, 연속선물 데이터는 직접 수집할 수 없다. 연속선물 시계열은 수집된 개별 월물 데이터를 롤오버
 * 기준으로 합성하여 생성해야 하며, 합성된 행의 {@code contractCode}에는 반드시 {@link #CONTINUOUS_CONTRACT_CODE}를 사용한다.
 */
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

    /** 연속선물 식별 코드. {@code contract_code} 컬럼에 저장되는 값. */
    public static final String CONTINUOUS_CONTRACT_CODE = "CONTINUOUS";

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

    @Column(name = "open_price", precision = 18, scale = 6)
    private final BigDecimal openPrice;

    @Column(name = "high_price", precision = 18, scale = 6)
    private final BigDecimal highPrice;

    @Column(name = "low_price", precision = 18, scale = 6)
    private final BigDecimal lowPrice;

    @Column(name = "close_price", precision = 18, scale = 6)
    private final BigDecimal closePrice;

    @Column(name = "volume")
    private final Long volume;

    @Column(name = "open_interest")
    private final Long openInterest;

    @Builder
    private FuturesDaily(
            String seriesCode,
            String contractCode,
            String exchangeCode,
            LocalDate tradeDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            Long volume,
            Long openInterest) {
        super();
        this.seriesCode = seriesCode;
        this.contractCode = contractCode;
        this.exchangeCode = exchangeCode;
        this.tradeDate = tradeDate;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
        this.volume = volume;
        this.openInterest = openInterest;
    }
}
