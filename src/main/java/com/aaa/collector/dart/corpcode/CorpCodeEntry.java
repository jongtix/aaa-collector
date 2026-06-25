package com.aaa.collector.dart.corpcode;

import java.time.LocalDate;

/**
 * DART corpCode.xml 단건 파싱 결과 (SPEC-COLLECTOR-DART-001 REQ-DART-002).
 *
 * <p>corp_code, corp_name, stock_code, modify_date 4개 필드를 담는다. stock_code가 비어 있으면 비상장사이므로 저장하지
 * 않는다(REQ-DART-002).
 */
public record CorpCodeEntry(
        String corpCode, String corpName, String stockCode, LocalDate modifyDate) {

    /** 상장사 여부 — stock_code가 비어 있지 않으면 상장사(저장 대상). */
    public boolean isListed() {
        return stockCode != null && !stockCode.isBlank();
    }
}
