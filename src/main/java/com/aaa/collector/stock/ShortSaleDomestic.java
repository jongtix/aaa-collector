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

    /**
     * KIS 누적 거래량(acml_vol, 수정주가 기준, 원본 무변환). 결측 시 NULL(과거 행 미백필).
     *
     * <p>SPEC-COLLECTOR-SHORTSALE-ACMLVOL-001 REQ-SSAV-002 — {@code short_sell_vol_rate} 진단용 분모 확보
     * 목적(aaa-infra#61), NOT NULL 제약 없음(Flyway DDL 단독 관리).
     */
    @Column(name = "acml_vol")
    private final Long acmlVol;

    /**
     * {@code short_sell_vol_rate}가 정정 공식(REQ-SSVC-011)으로 검증·정정된 시각. NULL이면 미검증 — Track 2(상시 재계산 스윕,
     * REQ-SSVC-034)의 pending 마커다.
     *
     * <p>SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-034 — 컬럼은 M8 마이그레이션(V45)에서 추가된다.
     * NOT NULL 제약 없음(Flyway DDL 단독 관리). UPDATE 경로는 네이티브 쿼리이므로 엔티티에 setter를 두지 않는다 — 이 필드는 SELECT 결과
     * 매핑 전용이다.
     */
    @Column(name = "vol_rate_verified_at")
    private final LocalDateTime volRateVerifiedAt;

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
            BigDecimal shortSellAccAmtRate,
            Long acmlVol,
            LocalDateTime volRateVerifiedAt) {
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
        this.acmlVol = acmlVol;
        this.volRateVerifiedAt = volRateVerifiedAt;
    }
}
