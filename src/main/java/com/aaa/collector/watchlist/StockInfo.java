package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetaInfo;
import java.time.LocalDate;

// @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001
/**
 * 종목 기본정보 API에서 조회한 종목 상세 데이터.
 *
 * @param assetType 종목 유형 (ETF, ETN, STOCK 등)
 * @param nameEn 영문 종목명
 * @param listedDate 상장일
 * @param etfMetaInfo ETF인 경우 ETF 메타정보 (ETF가 아니면 null)
 * @param market 권위 시장 — 국내는 {@code mket_id_cd} 기반 KOSPI/KOSDAQ 확정값, 해외는 거래소 라우팅 시장
 *     (REQ-STOCKMETA-001). {@code null}이면 정적 자산유형 분류 경로(시장 정보 없음).
 */
record StockInfo(
        AssetType assetType,
        String nameEn,
        LocalDate listedDate,
        EtfMetaInfo etfMetaInfo,
        Market market) {

    /** Convenience constructor for non-ETF stocks (market null — static-asset path or legacy). */
    StockInfo(AssetType assetType, String nameEn, LocalDate listedDate) {
        this(assetType, nameEn, listedDate, null, null);
    }

    /** Convenience constructor for non-ETF stocks with market. */
    StockInfo(AssetType assetType, String nameEn, LocalDate listedDate, Market market) {
        this(assetType, nameEn, listedDate, null, market);
    }
}
