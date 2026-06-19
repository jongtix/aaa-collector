package com.aaa.collector.stock.shortsale.overseas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FINRA {@code consolidatedShortInterest}(미국 반월 공매도 잔고, exchange-listed 커버) 응답 단일 행.
 *
 * <p>{@code currentShortPositionQuantity → short_interest}, {@code settlementDate → trade_date 및
 * short_interest_date}로 적재한다(명세 01). {@code revisionFlag="R"}(직전 사이클 잔고 수정)이면 이미 적재된
 * settlementDate라도 interest 컬럼을 갱신한다(REQ-SSO-014b). {@code float_shares}/{@code si_pct_float}는
 * FINRA 미제공으로 적재하지 않으며, daysToCoverQuantity 등 V7 컬럼 부재 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinraConsolidatedShortInterestResponse(
        @JsonProperty("symbolCode") String symbolCode,
        @JsonProperty("settlementDate") LocalDate settlementDate,
        @JsonProperty("currentShortPositionQuantity") BigDecimal currentShortPositionQuantity,
        @JsonProperty("revisionFlag") String revisionFlag) {}
