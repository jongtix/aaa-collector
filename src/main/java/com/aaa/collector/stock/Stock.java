package com.aaa.collector.stock;

import com.aaa.collector.common.entity.BaseEntity;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 종목 마스터. */
@Entity
@Table(
        name = "stocks",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_stocks_symbol_market",
                        columnNames = {"symbol", "market"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
public class Stock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", length = 16)
    private final String symbol;

    @Column(name = "name_ko", length = 100)
    private String nameKo;

    @Column(name = "name_en", length = 100)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 10)
    private final Market market;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", length = 10)
    private final AssetType assetType;

    @Column(name = "listed_date")
    private final LocalDate listedDate;

    @Column(name = "active")
    private boolean active;

    @Column(name = "watchlist_removed_at")
    private LocalDateTime watchlistRemovedAt;

    @Builder
    private Stock(
            String symbol,
            String nameKo,
            String nameEn,
            Market market,
            AssetType assetType,
            LocalDate listedDate,
            boolean active,
            LocalDateTime watchlistRemovedAt) {
        super();
        this.symbol = symbol;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.market = market;
        this.assetType = assetType;
        this.listedDate = listedDate;
        this.active = active;
        this.watchlistRemovedAt = watchlistRemovedAt;
    }

    /**
     * 관심종목 동기화 시 변경 가능한 필드를 갱신한다.
     *
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    public boolean updateNames(String nameKo, String nameEn) {
        boolean changed = false;

        if (nameKo != null && !Objects.equals(nameKo, this.nameKo)) {
            this.nameKo = nameKo;
            changed = true;
        }
        if (nameEn != null && !Objects.equals(nameEn, this.nameEn)) {
            this.nameEn = nameEn;
            changed = true;
        }
        if (!this.active) {
            this.active = true;
            changed = true;
        }
        if (this.watchlistRemovedAt != null) {
            this.watchlistRemovedAt = null; // NOPMD: intentional null reset for JPA dirty checking
            changed = true;
        }

        return changed;
    }
}
