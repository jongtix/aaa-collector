package com.aaa.collector.watchlist;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetaInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
        AssetType assetType = resolveAssetTypeDomestic(out.sctyGrpIdCd());
        String rawDate = market == Market.KOSPI ? out.sctsMketLstgDt() : out.kosdaqMketLstgDt();

        EtfMetaInfo etfMetaInfo = null;
        if (assetType == AssetType.ETF) {
            etfMetaInfo = extractDomesticEtfMeta(out);
        }

        return new StockInfo(assetType, out.prdtEngName(), parseDate(rawDate), etfMetaInfo);
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
        AssetType assetType =
                resolveAssetTypeOverseas(out.ovrsStckDvsnCd(), out.ovrsStckEtfRiskDrtpCd());

        EtfMetaInfo etfMetaInfo = null;
        if (assetType == AssetType.ETF) {
            etfMetaInfo = extractOverseasEtfMeta(out);
        }

        return new StockInfo(assetType, out.prdtEngName(), parseDate(out.lstgDt()), etfMetaInfo);
    }

    // @MX:WARN: [AUTO] ETF attribute derivation from undocumented KIS fields
    // @MX:REASON: etf_nasc_tp_cd and etf_chas_erng_rt_dbnb are not in official KIS docs;
    //             inverse/leveraged/hedged detection may misclassify if KIS changes field
    // semantics.
    private EtfMetaInfo extractDomesticEtfMeta(KisDomesticStockInfoResponse.Output out) {
        // Determine leverage and inverse from tracking ratio field
        // Negative value indicates inverse; absolute value is the leverage multiplier
        int leverage = 1;
        boolean inverse = false;
        String erngRt = out.etfChasErngRtDbnb();
        if (erngRt != null && !erngRt.isBlank()) {
            try {
                BigDecimal ratio = new BigDecimal(erngRt.trim());
                if (ratio.signum() < 0) {
                    inverse = true;
                }
                leverage = ratio.abs().intValue();
                if (leverage == 0) {
                    leverage = 1;
                }
            } catch (NumberFormatException ignored) {
                // fallback: leverage=1, inverse=false
            }
        }

        // Determine hedged from ETF type code
        // @MX:NOTE: [AUTO] etf_nasc_tp_cd values are empirically observed, not documented by KIS
        boolean hedged = isHedged(out.etfNascTpCd());

        return new EtfMetaInfo(
                blankToNull(out.etfTrgtNmixBstpCode()),
                leverage,
                inverse,
                hedged,
                false); // tr_stop is set separately via market data
    }

    private EtfMetaInfo extractOverseasEtfMeta(KisOverseasStockInfoResponse.Output out) {
        int leverage = 1;
        boolean inverse = false;
        String erngRt = out.ovrsEtfChasErngRtDbnb();
        if (erngRt != null && !erngRt.isBlank()) {
            try {
                BigDecimal ratio = new BigDecimal(erngRt.trim());
                if (ratio.signum() < 0) {
                    inverse = true;
                }
                leverage = ratio.abs().intValue();
                if (leverage == 0) {
                    leverage = 1;
                }
            } catch (NumberFormatException ignored) {
                // fallback: leverage=1, inverse=false
            }
        }

        return new EtfMetaInfo(
                blankToNull(out.ovrsEtfTrgtNmixCd()),
                leverage,
                inverse,
                false, // overseas ETFs: hedged not tracked
                false);
    }

    private boolean isHedged(String etfNascTpCd) {
        // Empirically observed: type codes containing "H" suffix indicate currency-hedged variants
        if (etfNascTpCd == null || etfNascTpCd.isBlank()) {
            return false;
        }
        String code = etfNascTpCd.trim().toUpperCase(Locale.ROOT);
        return code.endsWith("H") || code.contains("HEDGE");
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
        // KIS "날짜 없음" 특수값: 모든 자리가 '0' (e.g. "00000000")
        if (yyyymmdd.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE);
    }
}
