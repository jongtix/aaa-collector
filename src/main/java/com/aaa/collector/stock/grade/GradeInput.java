package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.enums.AssetType;

/**
 * 등급 분류를 위한 입력 데이터.
 *
 * @param symbol 종목코드
 * @param nameKo 종목 한글명 (F 등급 판정에 사용)
 * @param assetType 자산 유형 (ETF 판정에 사용)
 * @param holdingDays 보유 일봉 행 수 (daily_ohlcv COUNT — listed_date 불사용)
 * @param adtv 최근 20거래일 평균 거래대금 (native 통화, KRW 또는 USD)
 * @param market 시장 구분 문자열 (KRX 또는 US)
 */
public record GradeInput(
        String symbol,
        String nameKo,
        AssetType assetType,
        long holdingDays,
        double adtv,
        String market) {}
