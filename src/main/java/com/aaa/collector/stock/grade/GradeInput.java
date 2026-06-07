package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.enums.AssetType;

/**
 * 등급 분류를 위한 입력 데이터.
 *
 * @param symbol 종목코드
 * @param nameKo 종목 한글명 (F 등급 판정에 사용)
 * @param assetType 자산 유형 (ETF 판정에 사용)
 * @param listedYears 상장 경과 연수 (소수점 포함 가능)
 * @param percentile 시장 내 ADTV 백분위 (0~100, 낮을수록 상위. 1위 ≈ 0에 가까운 값)
 */
public record GradeInput(
        String symbol, String nameKo, AssetType assetType, double listedYears, double percentile) {}
