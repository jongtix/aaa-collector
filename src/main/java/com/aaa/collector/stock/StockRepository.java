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

    /**
     * per-stock J-API 배치(일봉/수급) 대상 자산 유형 — STOCK·ETF·ETN·COMMODITY 포함, INDEX 제외(REQ-BATCH3-024,
     * SPEC-COLLECTOR-ASSETSCOPE-001 REQ-ASSETSCOPE-001).
     */
    Set<AssetType> TRADABLE_ASSET_TYPES =
            Set.of(AssetType.STOCK, AssetType.ETF, AssetType.ETN, AssetType.COMMODITY);

    /** 미국 일봉 배치 대상 시장 — NYSE/NASDAQ/AMEX(REQ-OVOH-001). */
    Set<Market> OVERSEAS_TRADABLE_MARKETS = Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    /** 국내 수급 배치 대상 시장 — KOSPI/KOSDAQ/KRX. KIS 국내 API는 미국 종목에 null-dated 빈 행 반환. */
    Set<Market> DOMESTIC_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ, Market.KRX);

    Optional<Stock> findBySymbolAndMarket(String symbol, Market market);

    List<Stock> findAllBySymbolIn(Collection<String> symbols);

    /**
     * 관심목록에서 제거되지 않은(active 무관) 종목 ID 전체를 조회한다 — {@code markRemoved} 후보 기준집합
     * (SPEC-COLLECTOR-WLSYNC-009 REQ-WLSYNC-159).
     *
     * <p>{@link #findAllActive()} 등 기존 조회 메서드는 {@code active = true}도 함께 걸러 이 용도에 부적합하다 —
     * 상폐·거래정지(active=false)라도 관심그룹에서 아직 제거되지 않은 종목은 이 기준집합에 포함되어야 후보 산정이 정확하다(active 축과 watchlist
     * 제거 축은 서로 독립, {@link #findAllActive()} Javadoc 참조).
     */
    @Query("SELECT s.id FROM Stock s WHERE s.watchlistRemovedAt IS NULL")
    Set<Long> findIdsByWatchlistRemovedAtIsNull();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE Stock s SET s.watchlistRemovedAt = CURRENT_TIMESTAMP WHERE s.id IN :ids AND s.watchlistRemovedAt IS NULL")
    void markWatchlistRemoved(@Param("ids") Set<Long> ids);

    /**
     * 관심 목록에서 제거되지 않고 시장에 유효한(상폐·거래정지 아닌) 종목을 조회한다.
     *
     * <p>"활성"의 기준은 서로 독립인 두 게이트의 AND다(SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148):
     *
     * <ul>
     *   <li>{@code active = true} — 시장 유효성(상폐·거래정지 아님). {@code delisted_at}은 이 축의 필터 조건이 아니라 {@code
     *       active=false}의 사유 메타데이터일 뿐이다(REQ-WLSYNC-149, 3축 추가 금지).
     *   <li>{@code watchlistRemovedAt IS NULL} — 수집 의사(운영자가 관심그룹에 두고 있는가). 가장 최근 sync에서 제거 대상으로
     *       마킹되지 않은 종목.
     * </ul>
     *
     * <p>두 게이트는 하나의 필드로 합쳐지지 않고 항상 독립적으로 평가된다 — 상폐이면서 동시에 관심그룹에서도 제거된 종목도 각 축이 개별적으로 배제 사유가 될 뿐,
     * 서로의 상태를 변경하지 않는다.
     */
    @Query("SELECT s FROM Stock s WHERE s.active = true AND s.watchlistRemovedAt IS NULL")
    List<Stock> findAllActive();

    /**
     * per-stock J-API 배치(일봉/수급) 대상 종목을 조회한다 — {@code asset_type IN (STOCK, ETF, ETN, COMMODITY)}
     * 한정.
     *
     * <p>REQ-BATCH3-024: INDEX 종목을 J API로 헛호출(빈 응답·rate-limit 낭비)하는 비효율 제거. 지수는 U 전용 API(T3
     * SectorIndexCollectionService)로 수집하므로 per-stock 배치에서 제외한다. SPEC-COLLECTOR-ASSETSCOPE-001
     * REQ-ASSETSCOPE-001: ETN·COMMODITY를 대상에 편입(INDEX 배제는 유지).
     *
     * <p>호출자는 {@code AssetType}을 직접 참조하지 않아도 된다. TRADABLE 집합은 이 레포지토리 계층에서만 관리한다.
     */
    default List<Stock> findAllActiveTradable() {
        return findAllActiveByAssetTypeIn(TRADABLE_ASSET_TYPES);
    }

    /**
     * per-stock 배치(일봉/수급) 대상 종목을 조회한다 — {@code asset_type IN (STOCK, ETF, ETN, COMMODITY)} 한정.
     *
     * <p>REQ-BATCH3-024: INDEX 종목을 J API로 헛호출(빈 응답·rate-limit 낭비)하는 비효율 제거. 지수는 U 전용 API(T3
     * SectorIndexCollectionService)로 수집하므로 per-stock 배치에서 제외한다.
     *
     * <p>2축 직교 필터(SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148): {@code active = true}(시장 유효성) AND
     * {@code watchlistRemovedAt IS NULL}(수집 범위). {@link #findAllActive()} 참조.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.active = true AND s.watchlistRemovedAt IS NULL"
                    + " AND s.assetType IN :assetTypes")
    List<Stock> findAllActiveByAssetTypeIn(@Param("assetTypes") Collection<AssetType> assetTypes);

    /**
     * 국내 수급 배치 대상 종목을 조회한다 — 활성({@code watchlistRemovedAt IS NULL}) ∩ {@code market IN (KOSPI,
     * KOSDAQ, KRX)} ∩ {@code asset_type IN (STOCK, ETF, ETN, COMMODITY)}.
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
     * NASDAQ, AMEX)} ∩ {@code asset_type IN (STOCK, ETF, ETN, COMMODITY)}
     * (SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-001). ETN·COMMODITY는 전부 KOSPI 시장이라 미국 시장 필터에 의해
     * 자동 배제되며 실질 반환 집합은 STOCK·ETF뿐이다 (SPEC-COLLECTOR-ASSETSCOPE-001 Exclusions §4).
     *
     * <p>국내 일봉/수급 배치가 사용하는 {@link #findAllActiveTradable()}(시장 무관 STOCK·ETF·ETN·COMMODITY)와 분리된 미국
     * 시장 한정 조회다 — 국내 종목·미국 INDEX를 제외한다. 기존 {@link #findAllActive()}/{@link
     * #findAllActiveTradable()}/{@link #findAllActiveStock()}는 변경하지 않는다.
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
     *
     * <p>2축 직교 필터(SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148): {@code active = true}(시장 유효성) AND
     * {@code watchlistRemovedAt IS NULL}(수집 범위). {@link #findAllActive()} 참조.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.active = true AND s.watchlistRemovedAt IS NULL"
                    + " AND s.market IN :markets AND s.assetType IN :assetTypes")
    List<Stock> findAllActiveByMarketInAndAssetTypeIn(
            @Param("markets") Collection<Market> markets,
            @Param("assetTypes") Collection<AssetType> assetTypes);

    /**
     * 종목 단위 펀더멘털 배치(재무비율/투자의견) 대상 종목을 조회한다 — {@code asset_type = STOCK} 한정
     * (SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-013).
     *
     * <p>재무비율·투자의견은 일반 주식 지표라 ETF/INDEX/ETN/COMMODITY엔 무의미·빈 응답이 온다. 따라서 활성(2축 직교 필터, {@link
     * #findAllActive()} 참조) 종목 중 {@code asset_type = STOCK}만 반환한다. BATCH-001/002(일봉/수급)의 {@link
     * #findAllActiveTradable()}({@code IN (STOCK, ETF)})와는 별개의 자체 필터다 — 기존 {@link
     * #findAllActive()}·{@link #findAllActiveTradable()}는 변경하지 않는다.
     *
     * <p>2축 직교 필터(SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148): {@code active = true}(시장 유효성) AND
     * {@code watchlistRemovedAt IS NULL}(수집 범위).
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.active = true AND s.watchlistRemovedAt IS NULL"
                    + " AND s.assetType = com.aaa.collector.stock.enums.AssetType.STOCK")
    List<Stock> findAllActiveStock();

    /**
     * 기존 INDEX 종목 행을 조회한다 — {@code asset_type=INDEX}, {@code market=KRX}, {@code symbol IN
     * symbols}.
     *
     * <p>REQ-BATCH3-021: watchlist sync가 등록한 INDEX 행(0001/1001)을 SectorIndexCollectionService가
     * 활용한다. 신규 등록 없음(CR-02).
     *
     * <p>2축 직교 필터(SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148): {@code active = true}(시장 유효성) AND
     * {@code watchlistRemovedAt IS NULL}(수집 범위). {@link #findAllActive()} 참조.
     */
    @Query(
            "SELECT s FROM Stock s WHERE s.assetType = com.aaa.collector.stock.enums.AssetType.INDEX"
                    + " AND s.market = :market AND s.symbol IN :symbols"
                    + " AND s.active = true AND s.watchlistRemovedAt IS NULL")
    List<Stock> findActiveIndexByMarketAndSymbolIn(
            @Param("market") Market market, @Param("symbols") Collection<String> symbols);
}
