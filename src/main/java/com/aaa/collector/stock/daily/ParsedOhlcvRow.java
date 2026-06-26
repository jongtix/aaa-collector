package com.aaa.collector.stock.daily;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일봉 OHLCV 행의 파싱 결과를 담는 불변 값 객체 (REQ-INSERT-001).
 *
 * <p>KIS 응답 문자열을 검증 단계에서 1회만 파싱하고, 불일치 탐지({@link MismatchDetector})·JDBC 바인딩({@link
 * WarningCountingOhlcvInserter})이 이 결과를 재사용하여 중복 파싱을 제거한다 (REQ-INSERT-002, 003, 004 W-1 불변식).
 *
 * <p>종목 ID({@code stockId})는 이 레코드에 포함하지 않는다 — 삽입 시 호출자가 별도로 전달한다(단일 종목 배치).
 *
 * @param tradeDate 거래일 (LocalDate 파싱 1회)
 * @param open 시가 (BigDecimal 파싱 1회)
 * @param high 고가
 * @param low 저가
 * @param close 종가
 * @param volume 거래량 (Long 파싱 1회)
 * @param tradingValue 거래대금 (Long 파싱 1회)
 */
// @MX:NOTE: [AUTO] W-1 불변식의 중간 표현 — 검증·불일치·바인딩이 공유하는 파싱 결과 캐리어
// @MX:REASON: REQ-INSERT-001,002,003,004 — 행당 BigDecimal/Long/LocalDate 파싱 1회 보장
public record ParsedOhlcvRow(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        long tradingValue) {}
