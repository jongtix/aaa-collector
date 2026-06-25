package com.aaa.collector.stock.exthours;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시간외 가격 수집 결과 전송 객체 (SPEC-COLLECTOR-EXTHOURS-001 REQ-EXTH-020~025).
 *
 * @param stockId 종목 ID
 * @param session PRE 또는 AFTER
 * @param tradeDate 거래일
 * @param extPrice 시간외 마지막 close 가격
 * @param referenceClose 갭 기준 종가 (PRE: 전일 종가, AFTER: 당일 종가)
 * @param source 데이터 소스 (항상 "YAHOO")
 */
public record ExtendedHoursRow(
        Long stockId,
        Session session,
        LocalDate tradeDate,
        BigDecimal extPrice,
        BigDecimal referenceClose,
        String source) {}
