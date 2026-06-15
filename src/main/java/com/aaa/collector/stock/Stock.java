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

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
    @Enumerated(EnumType.STRING)
    @Column(name = "market", length = 10)
    private Market market; // correctable — REQ-STOCKMETA-004,011

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", length = 10)
    private final AssetType assetType;

    @Column(name = "listed_date")
    private LocalDate listedDate; // correctable — REQ-STOCKMETA-004,012

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
     * 관심종목 동기화 시 이름 갱신, 재활성화, 제거 시각 초기화를 수행한다.
     *
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    public boolean syncFromWatchlist(String nameKo, String nameEn) {
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

    /**
     * 시장 교정 및 상장일 채우기를 수행한다 (REQ-STOCKMETA-004,011,012, SPEC-COLLECTOR-STOCKMETA-001).
     *
     * <p>두 보정 모두 단방향 강화만 수행한다:
     *
     * <ul>
     *   <li>시장 교정: 저장값이 {@code authoritativeMarket}과 다른 경우에만 교정한다. 이미 일치하면 변경하지 않는다.
     *   <li>상장일 채우기: 저장값이 {@code null}이고 {@code authoritative listedDate}가 non-null인 경우에만 채운다.
     *       non-null → non-null 덮어쓰기나 null로의 변경은 수행하지 않는다.
     * </ul>
     *
     * @param authoritativeMarket 권위 시장값 (null이면 시장 교정을 수행하지 않음)
     * @param authoritativeListedDate 권위 상장일 (null이면 상장일 채우기를 수행하지 않음)
     * @return 하나 이상의 필드가 실제로 변경된 경우 {@code true}
     */
    public boolean correctMetadata(Market authoritativeMarket, LocalDate authoritativeListedDate) {
        boolean changed = false;

        // 시장 교정: 저장값 != 권위값인 경우에만 (REQ-STOCKMETA-011)
        if (authoritativeMarket != null && !Objects.equals(this.market, authoritativeMarket)) {
            this.market = authoritativeMarket;
            changed = true;
        }

        // 상장일 채우기: NULL→non-null만 수행 (REQ-STOCKMETA-012)
        if (this.listedDate == null && authoritativeListedDate != null) {
            this.listedDate = authoritativeListedDate;
            changed = true;
        }

        return changed;
    }
}
