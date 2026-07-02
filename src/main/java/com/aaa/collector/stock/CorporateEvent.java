package com.aaa.collector.stock;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.stock.enums.EventType;
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

/** 기업 이벤트 (배당/증자/분할/어닝). Phase 1 수집 대상: DIVIDEND. */
@Entity
@Table(
        name = "corporate_events",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_corporate_events",
                        columnNames = {"stock_id", "event_type", "event_date", "event_subtype"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class CorporateEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_corporate_events_stock"))
    private final Stock stock;

    @Column(name = "event_type", length = 20)
    private final EventType eventType;

    @Column(name = "event_date")
    private final LocalDate eventDate;

    /**
     * 배당락일 (해외 현금배당 KIS {@code div_lock_dt} → V25 {@code ex_dividend_date}).
     *
     * <p>RD-8 [확정: A]: 해외 현금배당 수집용 1컬럼만 추가(nullable). 국내 배당 행은 영구 NULL(국내 배당 수집은 배당락일 미채움 — 비대칭
     * 인지·허용, REQ-OVE-062).
     */
    @Column(name = "ex_dividend_date")
    private final LocalDate exDividendDate;

    /**
     * 이벤트 세부 유형 (일반배당/특별배당/분할/병합 등) — 4컬럼 unique key 구성원(경로 A, REQ-ODA-045).
     *
     * <p>NOT NULL — MySQL unique index의 NULL 중복 허용으로 인한 {@code INSERT IGNORE} 멱등성 붕괴를 방지한다(V33).
     */
    @Column(name = "event_subtype", length = 20, nullable = false)
    private final String eventSubtype;

    @Column(name = "pay_date")
    private final LocalDate payDate;

    @Column(name = "stock_pay_date")
    private final LocalDate stockPayDate;

    @Column(name = "odd_pay_date")
    private final LocalDate oddPayDate;

    /**
     * 현금배당금 (통화={@link #currencyCode}). V33: {@code BIGINT} → {@code DECIMAL(15,5)}(REQ-ODA-040).
     */
    @Column(name = "cash_amount", precision = 15, scale = 5)
    private final BigDecimal cashAmount;

    /** 배당 통화 (KRW/USD 등, nullable — REQ-ODA-041). */
    @Column(name = "currency_code", length = 3)
    private final String currencyCode;

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
            LocalDate exDividendDate,
            String eventSubtype,
            LocalDate payDate,
            LocalDate stockPayDate,
            LocalDate oddPayDate,
            BigDecimal cashAmount,
            String currencyCode,
            BigDecimal cashRate,
            BigDecimal stockRate,
            Long faceValue,
            String stockKind,
            String highDividendFlag) {
        super();
        this.stock = stock;
        this.eventType = eventType;
        this.eventDate = eventDate;
        this.exDividendDate = exDividendDate;
        this.eventSubtype = eventSubtype;
        this.payDate = payDate;
        this.stockPayDate = stockPayDate;
        this.oddPayDate = oddPayDate;
        this.cashAmount = cashAmount;
        this.currencyCode = currencyCode;
        this.cashRate = cashRate;
        this.stockRate = stockRate;
        this.faceValue = faceValue;
        this.stockKind = stockKind;
        this.highDividendFlag = highDividendFlag;
    }
}
