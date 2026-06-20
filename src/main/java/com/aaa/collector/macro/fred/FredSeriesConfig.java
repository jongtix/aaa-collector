package com.aaa.collector.macro.fred;

import java.util.List;

/**
 * FRED 수집 대상 5개 시리즈 설정 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-021).
 *
 * <p>각 시리즈는 indicator_code와 FRED series_id로 구성된다.
 */
public final class FredSeriesConfig {

    /** 수집 대상 시리즈 목록 (순서 고정). */
    public static final List<Series> ALL =
            List.of(
                    new Series("FRED_DFF", "DFF"),
                    new Series("FRED_DGS10", "DGS10"),
                    new Series("FRED_CPIAUCSL", "CPIAUCSL"),
                    new Series("FRED_A191RL1Q225SBEA", "A191RL1Q225SBEA"),
                    new Series("FRED_UNRATE", "UNRATE"));

    private FredSeriesConfig() {}

    /**
     * FRED 시리즈 설정.
     *
     * @param indicatorCode macro_indicators.indicator_code
     * @param seriesId FRED API series_id
     */
    public record Series(String indicatorCode, String seriesId) {}
}
