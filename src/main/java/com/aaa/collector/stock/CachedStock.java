package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;

/**
 * Redis 캐시용 종목 DTO.
 *
 * <p>JPA 엔티티 직렬화를 피하고 캐시에 필요한 필드만 담는다.
 */
public record CachedStock(
        String symbol,
        String nameKo,
        String nameEn,
        Market market,
        AssetType assetType,
        LocalDate listedDate) {

    /**
     * {@link Stock} 엔티티로부터 {@code CachedStock}을 생성한다.
     *
     * @param stock 종목 엔티티
     * @return 캐시 DTO
     */
    public static CachedStock from(Stock stock) {
        return new CachedStock(
                stock.getSymbol(),
                stock.getNameKo(),
                stock.getNameEn(),
                stock.getMarket(),
                stock.getAssetType(),
                stock.getListedDate());
    }
}
