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
 * FINRA 미제공으로 적재하지 않는다. {@code issueName}/{@code daysToCoverQuantity}/{@code
 * averageDailyVolumeQuantity}는 V41(SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M2, REQ-SSOI-012)로 캡처를
 * 시작한다 — {@code issueName}은 게이트 제외 행의 진단 로깅 전용(DB 미저장, REQ-SSOI-009), {@code
 * daysToCoverQuantity}/{@code averageDailyVolumeQuantity}는 {@code days_to_cover}/{@code
 * avg_daily_volume} 컬럼으로 적재한다(REQ-SSOI-010/-011). 그 외 필드(accountingYearMonthNumber 등)는 계속
 * 무시한다({@link JsonIgnoreProperties}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FinraConsolidatedShortInterestResponse(
        @JsonProperty("symbolCode") String symbolCode,
        @JsonProperty("issueName") String issueName,
        @JsonProperty("settlementDate") LocalDate settlementDate,
        @JsonProperty("currentShortPositionQuantity") BigDecimal currentShortPositionQuantity,
        @JsonProperty("averageDailyVolumeQuantity") BigDecimal averageDailyVolumeQuantity,
        @JsonProperty("daysToCoverQuantity") BigDecimal daysToCoverQuantity,
        @JsonProperty("revisionFlag") String revisionFlag) {

    /**
     * 하위 호환 축약 생성자 — {@code issueName}/{@code averageDailyVolumeQuantity}/{@code
     * daysToCoverQuantity} 없이 4필드만으로 생성한다(SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M2 REQ-SSOI-012 확장
     * 이전 호출부 컴파일·동작 유지 목적). 신규 3필드는 {@code null}로 남는다.
     *
     * @param symbolCode FINRA 심볼 코드
     * @param settlementDate 정산일
     * @param currentShortPositionQuantity 당기 공매도 잔고
     * @param revisionFlag 직전 사이클 잔고 수정 플래그
     */
    public FinraConsolidatedShortInterestResponse(
            String symbolCode,
            LocalDate settlementDate,
            BigDecimal currentShortPositionQuantity,
            String revisionFlag) {
        this(
                symbolCode,
                null,
                settlementDate,
                currentShortPositionQuantity,
                null,
                null,
                revisionFlag);
    }
}
