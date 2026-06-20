package com.aaa.collector.market.indicator.usdkrw;

import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSource;
import java.time.LocalDate;
import java.util.List;

/**
 * 한국은행 ECOS USDKRW 환율 소스 포트 (SPEC-COLLECTOR-MARKETIND-001, REQ-015).
 *
 * <p>REQ-015: ECOS 포트만 정의. 어댑터 구현은 범위 외 — 미구현 시 Primary(KOREAEXIM)→Yahoo Fallback 체인으로 동작한다. 이
 * 인터페이스는 향후 ECOS 어댑터가 구현할 계약을 예약한다.
 */
public interface EcosExchangeRateSource extends MarketIndicatorSource {

    @Override
    default List<MarketIndicatorRow> fetchHistory() {
        return List.of();
    }

    @Override
    default List<MarketIndicatorRow> fetchDaily(LocalDate date) {
        return List.of();
    }

    @Override
    default String sourceName() {
        return "ECOS";
    }
}
