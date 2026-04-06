package com.aaa.collector.domain.stock.enums;

/**
 * 종목이 거래되는 시장.
 *
 * <p>개별 주식: KOSPI, KOSDAQ, NYSE, NASDAQ, AMEX
 *
 * <p>지수 종목 (코스피지수, S&P500 등 관심 종목에 포함되는 지수): KRX, US
 */
public enum Market {
    /** 한국거래소 유가증권시장 */
    KOSPI,
    /** 한국거래소 코스닥시장 */
    KOSDAQ,
    /** 뉴욕증권거래소 */
    NYSE,
    /** 나스닥 */
    NASDAQ,
    /** 미국증권거래소 */
    AMEX,
    /** 한국 지수 종목 전용 (코스피지수, 코스닥지수 등) */
    KRX,
    /** 미국 지수 종목 전용 (S&P500, 나스닥지수 등) */
    US
}
