package com.aaa.collector.stock.exthours;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.stock.Stock;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미국 시간외(Pre/After-Hours) 가격 스냅샷 엔티티 (SPEC-COLLECTOR-EXTHOURS-001).
 *
 * <p>Tier-1 테이블 — {@code collector} 사용자는 INSERT 권한만 보유(UPDATE 없음). 멱등 삽입은 반드시 {@code INSERT
 * IGNORE}를 사용한다(ADR-026, CLAUDE.md).
 */
@Entity
@Table(
        name = "extended_hours",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_extended_hours",
                        columnNames = {"stock_id", "session", "trade_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class ExtendedHours extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_extended_hours_stock"))
    private final Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "session")
    private final Session session;

    @Column(name = "trade_date")
    private final LocalDate tradeDate;

    @Column(name = "ext_price", precision = 18, scale = 4)
    private final BigDecimal extPrice;

    @Column(name = "reference_close", precision = 18, scale = 4)
    private final BigDecimal referenceClose;

    @Column(name = "source", length = 10)
    private final String source;

    @Column(name = "collected_at")
    private final LocalDateTime collectedAt;

    @Builder
    private ExtendedHours(
            Stock stock,
            Session session,
            LocalDate tradeDate,
            BigDecimal extPrice,
            BigDecimal referenceClose,
            String source,
            LocalDateTime collectedAt) {
        super();
        this.stock = stock;
        this.session = session;
        this.tradeDate = tradeDate;
        this.extPrice = extPrice;
        this.referenceClose = referenceClose;
        this.source = source;
        this.collectedAt = collectedAt;
    }
}
