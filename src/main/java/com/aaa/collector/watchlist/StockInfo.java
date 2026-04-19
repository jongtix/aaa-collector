package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.AssetType;
import java.time.LocalDate;

/** 종목 기본정보 API에서 조회한 종목 상세 데이터. */
record StockInfo(AssetType assetType, String nameEn, LocalDate listedDate) {}
