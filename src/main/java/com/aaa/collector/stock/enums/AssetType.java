package com.aaa.collector.stock.enums;

/** 자산 유형. */
public enum AssetType {
    /** 개별 주식 */
    STOCK,
    /** 상장지수펀드 */
    ETF,
    /** 상장지수증권 */
    ETN,
    /** 원자재 현물 (금현물 등) */
    COMMODITY,
    /** 지수 (코스피지수, S&P500 등) */
    INDEX
}
