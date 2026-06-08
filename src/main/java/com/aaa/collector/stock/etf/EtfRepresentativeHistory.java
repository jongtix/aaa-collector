package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ETF 대표 종목 변경 이력. Append-only — INSERT만 허용, UPDATE/DELETE 금지 (REQ-ETFHIST-001).
 *
 * <p>Note: BaseEntity를 상속하지 않음. history는 불변 레코드로 created_at/updated_at 감사 불필요.
 */
@Entity
@Table(
        name = "etf_representative_history",
        indexes = {
            @Index(
                    name = "idx_etf_rep_group_effective",
                    columnList = "group_key, effective_from DESC")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class EtfRepresentativeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_key", length = 200)
    private final String groupKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", foreignKey = @ForeignKey(name = "fk_etf_rep_stock"))
    private final Stock stock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prev_stock_id", foreignKey = @ForeignKey(name = "fk_etf_rep_prev_stock"))
    private final Stock prevStock;

    @Column(name = "effective_from")
    private final LocalDateTime effectiveFrom;

    @Builder
    private EtfRepresentativeHistory(
            String groupKey, Stock stock, Stock prevStock, LocalDateTime effectiveFrom) {
        this.groupKey = groupKey;
        this.stock = stock;
        this.prevStock = prevStock;
        this.effectiveFrom = effectiveFrom;
    }
}
