package com.aaa.collector.stock.etf;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.stock.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

/** ETF 종목별 메타데이터. 종목당 최대 1행 (UNIQUE on stock_id). */
@Entity
@Table(
        name = "etf_metadata",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_etf_metadata_stock",
                        columnNames = {"stock_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class EtfMetadata extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_etf_metadata_stock"))
    private final Stock stock;

    @Column(name = "underlying_index_code", length = 50)
    private String underlyingIndexCode;

    @JdbcTypeCode(Types.TINYINT)
    @Column(name = "leverage")
    private int leverage;

    @Column(name = "inverse")
    private boolean inverse;

    @Column(name = "hedged")
    private boolean hedged;

    @Column(name = "tr_stop")
    private boolean trStop;

    @Builder
    private EtfMetadata(
            Stock stock,
            String underlyingIndexCode,
            int leverage,
            boolean inverse,
            boolean hedged,
            boolean trStop) {
        super();
        this.stock = stock;
        this.underlyingIndexCode = underlyingIndexCode;
        this.leverage = leverage;
        this.inverse = inverse;
        this.hedged = hedged;
        this.trStop = trStop;
    }

    /**
     * Updates mutable fields from the latest ETF metadata.
     *
     * @return true if any field actually changed
     */
    public boolean updateFrom(
            String underlyingIndexCode,
            int leverage,
            boolean inverse,
            boolean hedged,
            boolean trStop) {
        boolean changed = false;
        if (!java.util.Objects.equals(this.underlyingIndexCode, underlyingIndexCode)) {
            this.underlyingIndexCode = underlyingIndexCode;
            changed = true;
        }
        if (this.leverage != leverage) {
            this.leverage = leverage;
            changed = true;
        }
        if (this.inverse != inverse) {
            this.inverse = inverse;
            changed = true;
        }
        if (this.hedged != hedged) {
            this.hedged = hedged;
            changed = true;
        }
        if (this.trStop != trStop) {
            this.trStop = trStop;
            changed = true;
        }
        return changed;
    }
}
