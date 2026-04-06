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

/** 종목 투자의견 (KIS 국내주식종목투자의견). */
@Entity
@Table(
        name = "analyst_estimates",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_analyst_estimates",
                        columnNames = {"stock_id", "trade_date", "institution_name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class AnalystEstimate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_analyst_estimates_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "institution_name", length = 50)
    private final String institutionName;

    @Column(name = "opinion", length = 40)
    private final String opinion;

    @Column(name = "opinion_code", length = 2)
    private final String opinionCode;

    @Column(name = "prev_opinion", length = 40)
    private final String prevOpinion;

    @Column(name = "prev_opinion_code", length = 2)
    private final String prevOpinionCode;

    @Column(name = "target_price")
    private final Long targetPrice;

    @Column(name = "prev_close")
    private final Long prevClose;

    @Column(name = "gap_n_day", precision = 12, scale = 4)
    private final BigDecimal gapNDay;

    @Column(name = "gap_rate_n_day", precision = 12, scale = 4)
    private final BigDecimal gapRateNDay;

    @Column(name = "gap_futures", precision = 12, scale = 4)
    private final BigDecimal gapFutures;

    @Column(name = "gap_rate_futures", precision = 12, scale = 4)
    private final BigDecimal gapRateFutures;

    @Builder
    private AnalystEstimate(
            Stock stock,
            LocalDate tradeDate,
            String institutionName,
            String opinion,
            String opinionCode,
            String prevOpinion,
            String prevOpinionCode,
            Long targetPrice,
            Long prevClose,
            BigDecimal gapNDay,
            BigDecimal gapRateNDay,
            BigDecimal gapFutures,
            BigDecimal gapRateFutures) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        this.institutionName = institutionName;
        this.opinion = opinion;
        this.opinionCode = opinionCode;
        this.prevOpinion = prevOpinion;
        this.prevOpinionCode = prevOpinionCode;
        this.targetPrice = targetPrice;
        this.prevClose = prevClose;
        this.gapNDay = gapNDay;
        this.gapRateNDay = gapRateNDay;
        this.gapFutures = gapFutures;
        this.gapRateFutures = gapRateFutures;
    }
}
