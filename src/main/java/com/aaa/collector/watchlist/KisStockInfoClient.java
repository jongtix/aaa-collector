package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** KIS 종목 기본정보 API 클라이언트. */
@Component
@RequiredArgsConstructor
public class KisStockInfoClient {

    private static final String TR_ID_DOMESTIC = "CTPF1002R";
    private static final String TR_ID_OVERSEAS = "CTPF1702R";

    private final KisApiExecutor kisApiExecutor;

    /**
     * 시장에 따라 국내/해외 종목 기본정보 API를 호출하여 {@link StockInfo}를 반환한다.
     *
     * <p>재시도 없음. 비즈니스 오류({@link com.aaa.collector.kis.KisApiBusinessException}) 및 네트워크 오류는 즉시 전파되며
     * 호출자({@code WatchlistSyncService})에서 null로 처리된다.
     *
     * @param symbol 종목코드
     * @param market 시장
     * @return 종목 기본정보
     */
    public StockInfo fetchStockInfo(String symbol, Market market) {
        return switch (market) {
            case KOSPI, KOSDAQ -> fetchDomesticInfo(symbol, market);
            case NYSE, NASDAQ, AMEX -> fetchOverseasInfo(symbol, market);
            default -> throw new IllegalArgumentException("종목 기본정보 조회를 지원하지 않는 시장: " + market);
        };
    }

    private StockInfo fetchDomesticInfo(String symbol, Market market) {
        KisDomesticStockInfoResponse response =
                kisApiExecutor.executeGet(
                        uri ->
                                uri.path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                                        .queryParam("PRDT_TYPE_CD", "300")
                                        .queryParam("PDNO", symbol)
                                        .build(),
                        TR_ID_DOMESTIC,
                        KisDomesticStockInfoResponse.class);

        KisDomesticStockInfoResponse.Output out = response.output();
        String rawDate = market == Market.KOSPI ? out.sctsMketLstgDt() : out.kosdaqMketLstgDt();
        return new StockInfo(
                resolveAssetTypeDomestic(out.sctyGrpIdCd()), out.prdtEngName(), parseDate(rawDate));
    }

    private StockInfo fetchOverseasInfo(String symbol, Market market) {
        String prdtTypeCd =
                switch (market) {
                    case NASDAQ -> "512";
                    case NYSE -> "513";
                    case AMEX -> "529";
                    default -> throw new IllegalArgumentException("지원하지 않는 해외 시장: " + market);
                };

        KisOverseasStockInfoResponse response =
                kisApiExecutor.executeGet(
                        uri ->
                                uri.path("/uapi/overseas-price/v1/quotations/search-info")
                                        .queryParam("PRDT_TYPE_CD", prdtTypeCd)
                                        .queryParam("PDNO", symbol)
                                        .build(),
                        TR_ID_OVERSEAS,
                        KisOverseasStockInfoResponse.class);

        KisOverseasStockInfoResponse.Output out = response.output();
        return new StockInfo(
                resolveAssetTypeOverseas(out.ovrsStckDvsnCd(), out.ovrsStckEtfRiskDrtpCd()),
                out.prdtEngName(),
                parseDate(out.lstgDt()));
    }

    private AssetType resolveAssetTypeDomestic(String sctyGrpIdCd) {
        return switch (sctyGrpIdCd) {
            case "EF", "FE" -> AssetType.ETF;
            case "EN" -> AssetType.ETN;
            default -> AssetType.STOCK;
        };
    }

    private AssetType resolveAssetTypeOverseas(String dvsnCd, String etfRiskCd) {
        if (!"03".equals(dvsnCd)) {
            return AssetType.STOCK;
        }
        return switch (etfRiskCd) {
            case "002", "006" -> AssetType.ETN;
            default -> AssetType.ETF;
        };
    }

    private LocalDate parseDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE);
    }
}
