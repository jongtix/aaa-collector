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

    /** 미국 일봉 배치 대상 시장 — NYSE/NASDAQ/AMEX(REQ-OVOH-001). */
    Set<Market> OVERSEAS_TRADABLE_MARKETS = Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    /** 국내 수급 배치 대상 시장 — KOSPI/KOSDAQ/KRX. KIS 국내 API는 미국 종목에 null-dated 빈 행 반환. */
    Set<Market> DOMESTIC_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);

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
     * 국내 수급 배치 대상 종목을 조회한다 — 활성({@code watchlistRemovedAt IS NULL}) ∩ {@code market IN (KOSPI,
     * KOSDAQ, KRX)} ∩ {@code asset_type IN (STOCK, ETF)}.
     *
     * <p>KIS 국내 수급 API(공매도·투자자동향·신용잔고)는 미국 종목(NYSE/NASDAQ/AMEX)에 대해 rt_cd=0이지만 날짜 필드가 null인 빈 객체
     * 배열을 반환한다. {@link #findAllActiveTradable()}(시장 무관)을 쓰면 미국 종목마다 API 낭비 + WARN 로그 노이즈가 발생한다. 국내
     * 수급 배치({@code DomesticSupplyDemandCollectionService})는 이 메서드를 사용한다.
     */
    default List<Stock> findAllActiveDomesticTradable() {
        return findAllActiveByMarketInAndAssetTypeIn(DOMESTIC_MARKETS, TRADABLE_ASSET_TYPES);
    }

    /**
     * 미국 일봉 배치 대상 종목을 조회한다 — 활성({@code watchlistRemovedAt IS NULL}) ∩ {@code market IN (NYSE,
     * NASDAQ, AMEX)} ∩ {@code asset_type IN (STOCK, ETF)} (SPEC-COLLECTOR-OVERSEAS-OHLCV-001
     * REQ-OVOH-001).
     *
     * <p>국내 일봉/수급 배치가 사용하는 {@link #findAllActiveTradable()}(시장 무관 STOCK+ETF)와 분리된 미국 시장 한정 조회다 — 국내
     * 종목·미국 INDEX를 제외한다. 기존 {@link #findAllActive()}/{@link #findAllActiveTradable()}/{@link
     * #findAllActiveStock()}는 변경하지 않는다.
     */
    default List<Stock> findAllActiveOverseasTradable() {
        return findAllActiveByMarketInAndAssetTypeIn(
                OVERSEAS_TRADABLE_MARKETS, TRADABLE_ASSET_TYPES);
    }

    /**
     * 활성 종목 중 지정 시장·자산 유형에 속한 종목을 조회한다 (REQ-OVOH-001).
     *
     * <p>호출자는 시장·자산 유형 집합을 직접 구성하지 않고 {@link #findAllActiveOverseasTradable()}를 사용한다 — 미국 일봉 배치 대상
     * 집합은 이 레포지토리 계층에서만 관리한다.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.watchlistRemovedAt IS NULL"
                    + " AND s.market IN :markets AND s.assetType IN :assetTypes")
    List<Stock> findAllActiveByMarketInAndAssetTypeIn(
            @Param("markets") Collection<Market> markets,
            @Param("assetTypes") Collection<AssetType> assetTypes);

    /**
     * 종목 단위 펀더멘털 배치(재무비율/투자의견) 대상 종목을 조회한다 — {@code asset_type = STOCK} 한정
     * (SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-013).
     *
     * <p>재무비율·투자의견은 일반 주식 지표라 ETF/INDEX/ETN/COMMODITY엔 무의미·빈 응답이 온다. 따라서 활성({@code
     * watchlistRemovedAt IS NULL}) 종목 중 {@code asset_type = STOCK}만 반환한다. BATCH-001/002(일봉/수급)의
     * {@link #findAllActiveTradable()}({@code IN (STOCK, ETF)})와는 별개의 자체 필터다 — 기존 {@link
     * #findAllActive()}·{@link #findAllActiveTradable()}는 변경하지 않는다.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.watchlistRemovedAt IS NULL"
                    + " AND s.assetType = com.aaa.collector.stock.enums.AssetType.STOCK")
    List<Stock> findAllActiveStock();

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
