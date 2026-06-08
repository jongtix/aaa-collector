package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.etf.EtfMetaInfo;
import java.time.LocalDate;

/**
 * 종목 기본정보 API에서 조회한 종목 상세 데이터.
 *
 * @param assetType 종목 유형 (ETF, ETN, STOCK 등)
 * @param nameEn 영문 종목명
 * @param listedDate 상장일
 * @param etfMetaInfo ETF인 경우 ETF 메타정보 (ETF가 아니면 null)
 */
record StockInfo(
        AssetType assetType, String nameEn, LocalDate listedDate, EtfMetaInfo etfMetaInfo) {

    /** Convenience constructor for non-ETF stocks. */
    StockInfo(AssetType assetType, String nameEn, LocalDate listedDate) {
        this(assetType, nameEn, listedDate, null);
    }
}
