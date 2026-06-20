package com.aaa.collector.macro.ecos;

import java.util.List;

/**
 * ECOS 수집 대상 8개 시리즈 설정 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-001).
 *
 * <p>각 시리즈는 indicator_code, 통계표코드(statCode), 항목코드(itemCode), 주기(period)로 구성된다. 주기는 D(일), M(월),
 * Q(분기)이며, 주기에 따라 수집 윈도우와 날짜 정규화 방식이 달라진다.
 */
public final class EcosSeriesConfig {

    private EcosSeriesConfig() {}

    /** 수집 대상 시리즈 목록 (순서 고정). */
    public static final List<Series> ALL =
            List.of(
                    new Series("ECOS_BASE_RATE", "722Y001", "0101000", "D"),
                    new Series("ECOS_GOV_BOND_3Y", "817Y002", "010200000", "D"),
                    new Series("ECOS_GOV_BOND_5Y", "817Y002", "010200001", "D"),
                    new Series("ECOS_GOV_BOND_10Y", "817Y002", "010210000", "D"),
                    new Series("ECOS_CORP_BOND", "817Y002", "010300000", "D"),
                    new Series("ECOS_CPI", "901Y009", "0", "M"),
                    new Series("ECOS_GDP_QOQ", "200Y102", "10111", "Q"),
                    new Series("ECOS_CURRENT_ACCOUNT", "301Y013", "000000", "M"));

    /**
     * ECOS 시리즈 설정.
     *
     * @param indicatorCode macro_indicators.indicator_code
     * @param statCode ECOS 통계표코드
     * @param itemCode ECOS 항목코드
     * @param period 주기 코드: D(일), M(월), Q(분기)
     */
    public record Series(String indicatorCode, String statCode, String itemCode, String period) {}
}
