package com.aaa.collector.stock.etf;

/**
 * ETF 종목 기본정보 API에서 추출한 ETF-specific 메타데이터.
 *
 * <p>group_key 형식: "{exchange}:{underlyingIndexCode}:{leverage}:{direction}:{hedged}" 예:
 * "KOSPI:069500:1:NORMAL:false"
 *
 * @param underlyingIndexCode underlying index code (e.g., "069500")
 * @param leverage leverage multiplier (absolute value, e.g., 1, 2)
 * @param inverse true if inverse ETF
 * @param hedged true if currency-hedged
 * @param trStop true if trading is halted
 */
// @MX:WARN: [AUTO] inverse/hedged/leverage derivation relies on undocumented KIS fields
// @MX:REASON: KIS API fields etf_type_cd and etf_chas_erng_rt_dbnb are not officially documented;
//             misclassification risk is high. Validate against actual API responses before
// trusting.
public record EtfMetaInfo(
        String underlyingIndexCode, int leverage, boolean inverse, boolean hedged, boolean trStop) {

    /**
     * Builds the group_key for ETF grouping.
     *
     * <p>Format: "{marketName}:{underlyingIndexCode}:{leverage}:{direction}:{hedged}" Direction is
     * "INVERSE" if inverse=true, otherwise "NORMAL".
     *
     * @param marketName exchange name (e.g., "KOSPI", "NASDAQ")
     * @return group_key string
     */
    public String buildGroupKey(String marketName) {
        String direction = inverse ? "INVERSE" : "NORMAL";
        String indexCode = underlyingIndexCode != null ? underlyingIndexCode : "UNKNOWN";
        return marketName + ":" + indexCode + ":" + leverage + ":" + direction + ":" + hedged;
    }
}
