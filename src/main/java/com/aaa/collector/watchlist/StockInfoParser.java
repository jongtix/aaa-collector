package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetaInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
/**
 * KIS 종목 기본정보 API 응답({@code search-stock-info} / {@code search-info})을 {@link StockInfo}로 파싱하는
 * 컴포넌트.
 *
 * <p>자산 유형 판정, ETF 메타 추출(레버리지/인버스/헤지), 상장일 파싱, 국내 KOSPI/KOSDAQ 권위 확정을 담당한다. URI 빌드·HTTP 호출 책임을 갖는
 * {@link KisStockInfoClient}와 분리하여 응집도를 높인다.
 */
@Slf4j
@Component
public class StockInfoParser {

    /**
     * 국내 종목 기본정보를 파싱한다.
     *
     * <p>KOSPI/KOSDAQ 권위 확정: {@code mket_id_cd}({@code STK}=KOSPI, {@code KSQ}=KOSDAQ)로 결정한다
     * (REQ-STOCKMETA-001, api-specs/kis/17-주식기본조회.md:41). 미매핑 값(예: KNX 코넥스)은 WARN 후 {@code null}
     * 반환(기존 미매핑 WARN-드롭 정책).
     *
     * <p>상장일 필드 선택: 확정된 시장 기준으로 KOSPI→{@code scts_mket_lstg_dt}, KOSDAQ→{@code
     * kosdaq_mket_lstg_dt}를 선택한다(REQ-STOCKMETA-030). {@code mket_id_cd} 미매핑 시 상장일도 null.
     *
     * @param out CTPF1002R 응답 출력
     * @param symbol 종목코드 (WARN 로깅용)
     * @return 파싱된 StockInfo. mket_id_cd 미매핑 시 null(기존 WARN-드롭 정책 준수).
     */
    StockInfo parseDomestic(KisDomesticStockInfoResponse.Output out, String symbol) {
        // mket_id_cd로 권위 KOSPI/KOSDAQ 확정 (REQ-STOCKMETA-001)
        Market authoritative = resolveDomesticMarket(out.mketIdCd(), symbol);
        if (authoritative == null) {
            // 미매핑 mket_id_cd — WARN-드롭 정책(기존 동작과 동일)
            return null;
        }

        AssetType assetType = resolveAssetTypeDomestic(out.sctyGrpIdCd());
        // 권위 시장 기준으로 상장일 필드 선택 (REQ-STOCKMETA-030)
        String rawDate =
                authoritative == Market.KOSPI ? out.sctsMketLstgDt() : out.kosdaqMketLstgDt();

        EtfMetaInfo etfMetaInfo = null;
        if (assetType == AssetType.ETF) {
            etfMetaInfo = extractDomesticEtfMeta(out);
        }

        // 상장폐지·거래정지 판정 (REQ-WLSYNC-142) — 판정 로직은 ListingStatusDetector로 위임(응집도).
        // "00000000"/공백 sentinel은 이 클래스의 parseDate가 이미 null로 정규화하므로 그대로 재사용한다.
        ListingStatusDetector.Detection detection =
                ListingStatusDetector.detectDomestic(
                        out.lstgAbolDt(), out.trStopYn(), this::parseDate);

        return new StockInfo(
                assetType,
                out.prdtEngName(),
                parseDate(rawDate),
                etfMetaInfo,
                authoritative,
                detection.status(),
                detection.delistedAt());
    }

    /**
     * {@code mket_id_cd} 값을 권위 국내 시장으로 변환한다.
     *
     * <p>{@code STK}=KOSPI, {@code KSQ}=KOSDAQ. 미매핑 값(예: KNX 코넥스)은 WARN 후 null 반환(기존 미매핑 WARN-드롭
     * 정책).
     */
    private Market resolveDomesticMarket(String mketIdCd, String symbol) {
        if (mketIdCd == null || mketIdCd.isBlank()) {
            log.warn("mket_id_cd 공백 — symbol={} 국내 시장 확정 불가, 종목 드롭", symbol);
            return null;
        }
        return switch (mketIdCd.trim()) {
            case "STK" -> Market.KOSPI;
            case "KSQ" -> Market.KOSDAQ;
            default -> {
                log.warn("mket_id_cd 미매핑 — symbol={} 종목 드롭: mket_id_cd={}", symbol, mketIdCd);
                yield null;
            }
        };
    }

    StockInfo parseOverseas(KisOverseasStockInfoResponse.Output out, Market market) {
        AssetType assetType =
                resolveAssetTypeOverseas(out.ovrsStckDvsnCd(), out.ovrsStckEtfRiskDrtpCd());

        EtfMetaInfo etfMetaInfo = null;
        if (assetType == AssetType.ETF) {
            etfMetaInfo = extractOverseasEtfMeta(out);
        }

        // 상장폐지·거래정지 판정 (REQ-WLSYNC-143) — 판정 로직은 ListingStatusDetector로 위임(응집도).
        ListingStatusDetector.Detection detection =
                ListingStatusDetector.detectOverseas(
                        out.lstgAbolItemYn(), out.ovrsStckTrStopDvsnCd());

        return new StockInfo(
                assetType,
                out.prdtEngName(),
                parseDate(out.lstgDt()),
                etfMetaInfo,
                market,
                detection.status(),
                detection.delistedAt());
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
        // KIS "날짜 없음" sentinel 처리. 문서 형식은 YYYYMMDD지만, 상장일 미상 종목(예: 일부 해외주식
        // JPM·GS)에는 공백+구분자 템플릿("    /  /")이나 전부 '0'("00000000")이 반환된다.
        // 구분자·공백을 제거해 숫자만 남긴 뒤, 비어있거나 전부 '0'이면 상장일 없음으로 간주한다.
        String digits = yyyymmdd.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE);
    }
}
