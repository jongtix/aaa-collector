package com.aaa.collector.domain.stock;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.domain.stock.enums.EventType;
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

/** 기업 이벤트 (배당/증자/분할/어닝). Phase 1 수집 대상: DIVIDEND. */
@Entity
@Table(
        name = "corporate_events",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_corporate_events",
                        columnNames = {"stock_id", "event_type", "event_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class CorporateEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_corporate_events_stock"))
    private final Stock stock;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 20)
    private final EventType eventType;

    @Column(name = "event_date")
    private final LocalDate eventDate;

    @Column(name = "event_subtype", length = 20)
    private final String eventSubtype;

    @Column(name = "pay_date")
    private final LocalDate payDate;

    @Column(name = "stock_pay_date")
    private final LocalDate stockPayDate;

    @Column(name = "odd_pay_date")
    private final LocalDate oddPayDate;

    @Column(name = "cash_amount")
    private final Long cashAmount;

    @Column(name = "cash_rate", precision = 12, scale = 4)
    private final BigDecimal cashRate;

    @Column(name = "stock_rate", precision = 12, scale = 4)
    private final BigDecimal stockRate;

    @Column(name = "face_value")
    private final Long faceValue;

    @Column(name = "stock_kind", length = 10)
    private final String stockKind;

    @Column(name = "high_dividend_flag", length = 1)
    private final String highDividendFlag;

    @Builder
    private CorporateEvent(
            Stock stock,
            EventType eventType,
            LocalDate eventDate,
            String eventSubtype,
            LocalDate payDate,
            LocalDate stockPayDate,
            LocalDate oddPayDate,
            Long cashAmount,
            BigDecimal cashRate,
            BigDecimal stockRate,
            Long faceValue,
            String stockKind,
            String highDividendFlag) {
        super();
        this.stock = stock;
        this.eventType = eventType;
        this.eventDate = eventDate;
        this.eventSubtype = eventSubtype;
        this.payDate = payDate;
        this.stockPayDate = stockPayDate;
        this.oddPayDate = oddPayDate;
        this.cashAmount = cashAmount;
        this.cashRate = cashRate;
        this.stockRate = stockRate;
        this.faceValue = faceValue;
        this.stockKind = stockKind;
        this.highDividendFlag = highDividendFlag;
    }
}
