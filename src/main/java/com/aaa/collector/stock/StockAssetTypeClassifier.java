package com.aaa.collector.stock;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 종목의 자산 유형을 시장과 종목코드 기반으로 분류하는 컴포넌트.
 *
 * <p>{@code WatchlistSyncService.fetchStockInfo()}의 인라인 분류 로직(125-126행)을 추출한 것이다. KIS API 호출이 필요 없는
 * 경우(KRX/US 지수, KOSPI 원자재)에만 값을 반환하고, 나머지는 {@link Optional#empty()}를 반환하여 KIS API 호출이 필요함을 나타낸다.
 *
 * @see com.aaa.collector.watchlist.WatchlistSyncService
 */
@Component
public class StockAssetTypeClassifier {

    /**
     * 시장과 종목코드를 기반으로 자산 유형을 분류한다.
     *
     * <p>KIS API 호출 없이 분류 가능한 경우에만 값을 반환한다:
     *
     * <ul>
     *   <li>KRX, US → {@link AssetType#INDEX}
     *   <li>KOSPI + 종목코드 M 접두사 → {@link AssetType#COMMODITY}
     * </ul>
     *
     * <p>그 외(KOSPI 일반, KOSDAQ, NYSE, NASDAQ, AMEX)는 KIS API 응답이 필요하므로 {@link Optional#empty()} 반환.
     *
     * @param market 종목 시장
     * @param symbol 종목코드
     * @return 분류된 자산 유형. KIS API 호출이 필요한 경우 {@link Optional#empty()}
     */
    public Optional<AssetType> classify(Market market, String symbol) {
        if (market == Market.KRX || market == Market.US) {
            return Optional.of(AssetType.INDEX);
        }
        if (market == Market.KOSPI && symbol.startsWith("M")) {
            return Optional.of(AssetType.COMMODITY);
        }
        return Optional.empty();
    }
}
