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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 미국 공매도 일별 (FINRA Daily Short Volume + Short Interest 병합). */
@Entity
@Table(
        name = "short_sale_overseas",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_short_sale_overseas",
                        columnNames = {"stock_id", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class ShortSaleOverseas extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_short_sale_overseas_stock"))
    private final Stock stock;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    // @MX:NOTE: [AUTO] short_volume/total_volume은 DECIMAL(20,6)(V34) — FINRA 2026-02-23 소수 정밀도 항구
    // 전환
    // 대응(SPEC-COLLECTOR-SHORTSALE-DECIMAL-001 REQ-SSD-001/002). DB는 NOT NULL DEFAULT 0이므로 생성자에서
    // null → ZERO로 정규화해 네이티브 UPSERT에 null이 새지 않도록 보장한다.
    // @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-DECIMAL-001
    @Column(name = "short_volume", precision = 20, scale = 6)
    private final BigDecimal shortVolume;

    @Column(name = "total_volume", precision = 20, scale = 6)
    private final BigDecimal totalVolume;

    @Column(name = "short_interest")
    private final Long shortInterest;

    @Column(name = "float_shares")
    private final Long floatShares;

    @Column(name = "si_pct_float", precision = 7, scale = 4)
    private final BigDecimal siPctFloat;

    @Column(name = "short_interest_date")
    private final LocalDate shortInterestDate;

    @Column(name = "daily_collected_at")
    private final LocalDateTime dailyCollectedAt;

    @Column(name = "interest_collected_at")
    private final LocalDateTime interestCollectedAt;

    @Builder
    private ShortSaleOverseas(
            Stock stock,
            LocalDate tradeDate,
            BigDecimal shortVolume,
            BigDecimal totalVolume,
            Long shortInterest,
            Long floatShares,
            BigDecimal siPctFloat,
            LocalDate shortInterestDate,
            LocalDateTime dailyCollectedAt,
            LocalDateTime interestCollectedAt) {
        super();
        this.stock = stock;
        this.tradeDate = tradeDate;
        // DB NOT NULL DEFAULT 0 정합 — null 거래량은 ZERO로 정규화(REQ-SSD-001)
        this.shortVolume = shortVolume == null ? BigDecimal.ZERO : shortVolume;
        this.totalVolume = totalVolume == null ? BigDecimal.ZERO : totalVolume;
        this.shortInterest = shortInterest;
        this.floatShares = floatShares;
        this.siPctFloat = siPctFloat;
        this.shortInterestDate = shortInterestDate;
        this.dailyCollectedAt = dailyCollectedAt;
        this.interestCollectedAt = interestCollectedAt;
    }
}
