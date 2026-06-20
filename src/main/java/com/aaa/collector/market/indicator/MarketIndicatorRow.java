package com.aaa.collector.market.indicator;

import com.aaa.collector.market.enums.IndicatorCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 소스별 정규화된 시장 지표 행 (SPEC-COLLECTOR-MARKETIND-001).
 *
 * <p>open/high/low는 USDKRW(KOREAEXIM/ECOS) 및 FRED VIX 소스에서 NULL이 허용된다(REQ-013, REQ-023).
 */
public record MarketIndicatorRow(
        IndicatorCode indicatorCode,
        LocalDate tradeDate,
        BigDecimal openValue,
        BigDecimal highValue,
        BigDecimal lowValue,
        BigDecimal closeValue,
        String source) {}
