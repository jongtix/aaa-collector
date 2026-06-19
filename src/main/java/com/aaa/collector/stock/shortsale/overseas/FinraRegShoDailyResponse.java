package com.aaa.collector.stock.shortsale.overseas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FINRA {@code regShoDaily}(미국 일별 공매도 거래량) 응답 단일 행.
 *
 * <p>한 종목·한 거래일을 보고 시설(reportingFacility)별로 여러 행 반환하므로, 적재 시 {@code (symbol, tradeReportDate)} 기준으로
 * {@code shortParQuantity}/{@code totalParQuantity}를 합산해야 한다(명세 00 §"집계 규칙"). 본 record는 합산에 필요한 필드만
 * 노출하고 나머지(reportingFacilityCode/marketCode/shortExemptParQuantity 등)는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinraRegShoDailyResponse(
        @JsonProperty("tradeReportDate") LocalDate tradeReportDate,
        @JsonProperty("securitiesInformationProcessorSymbolIdentifier") String symbol,
        @JsonProperty("shortParQuantity") BigDecimal shortParQuantity,
        @JsonProperty("totalParQuantity") BigDecimal totalParQuantity) {}
