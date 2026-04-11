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
    private final String nameKo;

    @Column(name = "name_en", length = 100)
    private final String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 10)
    private final Market market;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", length = 10)
    private final AssetType assetType;

    @Column(name = "listed_date")
    private final LocalDate listedDate;

    @Column(name = "active")
    private final boolean active;

    @Builder
    private Stock(
            String symbol,
            String nameKo,
            String nameEn,
            Market market,
            AssetType assetType,
            LocalDate listedDate,
            boolean active) {
        super();
        this.symbol = symbol;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.market = market;
        this.assetType = assetType;
        this.listedDate = listedDate;
        this.active = active;
    }
}
