package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetaInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * KIS 종목 기본정보 API 응답({@code search-stock-info} / {@code search-info})을 {@link StockInfo}로 파싱하는
 * 컴포넌트.
 *
 * <p>자산 유형 판정, ETF 메타 추출(레버리지/인버스/헤지), 상장일 파싱을 담당한다. URI 빌드·HTTP 호출 책임을 갖는 {@link
 * KisStockInfoClient}와 분리하여 응집도를 높인다.
 */
@Component
public class StockInfoParser {

    StockInfo parseDomestic(KisDomesticStockInfoResponse.Output out, Market market) {
        AssetType assetType = resolveAssetTypeDomestic(out.sctyGrpIdCd());
        String rawDate = market == Market.KOSPI ? out.sctsMketLstgDt() : out.kosdaqMketLstgDt();

        EtfMetaInfo etfMetaInfo = null;
        if (assetType == AssetType.ETF) {
            etfMetaInfo = extractDomesticEtfMeta(out);
        }
        return new StockInfo(assetType, out.prdtEngName(), parseDate(rawDate), etfMetaInfo);
    }

    StockInfo parseOverseas(KisOverseasStockInfoResponse.Output out) {
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
