package com.aaa.collector.stock.rights;

import java.math.BigDecimal;

/**
 * CTRGT011R 금액 맵 값 항목 — 권리유형별 금액/율/통화 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-014).
 *
 * @param rghtTypeCd 권리유형코드(03 일반배당/75 특별배당) — {@code event_subtype} 라벨 결정에 사용(REQ-ODA-046)
 * @param cashAmount {@code alct_frcr_unpr} 파싱값(DECIMAL(15,5) 경계, D5)
 * @param cashRate {@code cash_alct_rt} 파싱값(DECIMAL(12,4) 경계, D5)
 * @param stockRate {@code stck_alct_rt} 파싱값(DECIMAL(12,4) 경계, D5)
 * @param currencyCode {@code crcy_cd}(다중통화 crcy_cd2~4는 무시, D7)
 */
record DividendAmountItem(
        String rghtTypeCd,
        BigDecimal cashAmount,
        BigDecimal cashRate,
        BigDecimal stockRate,
        String currencyCode) {}
