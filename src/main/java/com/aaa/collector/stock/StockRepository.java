package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 종목 마스터 저장소. */
public interface StockRepository extends JpaRepository<Stock, Long> {

    /** per-stock J-API 배치(일봉/수급) 대상 자산 유형 — STOCK·ETF만 포함, INDEX 제외(REQ-BATCH3-024). */
    Set<AssetType> TRADABLE_ASSET_TYPES = Set.of(AssetType.STOCK, AssetType.ETF);

    Optional<Stock> findBySymbolAndMarket(String symbol, Market market);

    List<Stock> findAllBySymbolIn(Collection<String> symbols);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Stock s SET s.watchlistRemovedAt = CURRENT_TIMESTAMP WHERE s.id IN :ids AND s.watchlistRemovedAt IS NULL")
    void markWatchlistRemoved(@Param("ids") Set<Long> ids);

    /**
     * 관심 목록에서 제거되지 않은 종목을 조회한다.
     *
     * <p>"활성"의 기준: {@code watchlistRemovedAt IS NULL}. {@code active} 불리언 필드는 사용하지 않는다.
     * watchlistRemovedAt이 null인 종목은 가장 최근 sync에서 제거 대상으로 마킹되지 않은 종목이다.
     */
    @Query("SELECT s FROM Stock s WHERE s.watchlistRemovedAt IS NULL")
    List<Stock> findAllActive();

    /**
     * per-stock J-API 배치(일봉/수급) 대상 종목을 조회한다 — {@code asset_type IN (STOCK, ETF)} 한정.
     *
     * <p>REQ-BATCH3-024: INDEX 종목을 J API로 헛호출(빈 응답·rate-limit 낭비)하는 비효율 제거. 지수는 U 전용 API(T3
     * SectorIndexCollectionService)로 수집하므로 per-stock 배치에서 제외한다.
     *
     * <p>호출자는 {@code AssetType}을 직접 참조하지 않아도 된다. TRADABLE 집합은 이 레포지토리 계층에서만 관리한다.
     */
    default List<Stock> findAllActiveTradable() {
        return findAllActiveByAssetTypeIn(TRADABLE_ASSET_TYPES);
    }

    /**
     * per-stock 배치(일봉/수급) 대상 종목을 조회한다 — {@code asset_type IN (STOCK, ETF)} 한정.
     *
     * <p>REQ-BATCH3-024: INDEX 종목을 J API로 헛호출(빈 응답·rate-limit 낭비)하는 비효율 제거. 지수는 U 전용 API(T3
     * SectorIndexCollectionService)로 수집하므로 per-stock 배치에서 제외한다.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.watchlistRemovedAt IS NULL AND s.assetType IN :assetTypes")
    List<Stock> findAllActiveByAssetTypeIn(@Param("assetTypes") Collection<AssetType> assetTypes);

    /**
     * 기존 INDEX 종목 행을 조회한다 — {@code asset_type=INDEX}, {@code market=KRX}, {@code symbol IN
     * symbols}.
     *
     * <p>REQ-BATCH3-021: watchlist sync가 등록한 INDEX 행(0001/1001)을 SectorIndexCollectionService가
     * 활용한다. 신규 등록 없음(CR-02).
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.assetType = com.aaa.collector.stock.enums.AssetType.INDEX"
                    + " AND s.market = :market AND s.symbol IN :symbols AND s.watchlistRemovedAt IS NULL")
    List<Stock> findActiveIndexByMarketAndSymbolIn(
            @Param("market") Market market, @Param("symbols") Collection<String> symbols);
}
