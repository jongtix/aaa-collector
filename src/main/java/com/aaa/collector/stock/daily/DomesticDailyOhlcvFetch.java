package com.aaa.collector.stock.daily;

import java.time.LocalDate;
import java.util.List;

/**
 * 국내 일봉 백필 fetch 단계 결과 DTO.
 *
 * <p>REQ-INSERT-002: 검증 단계에서 파싱된 {@link ParsedOhlcvRow} 목록을 전달하여 persist 단계가 재파싱 없이 바인딩한다 (W-1
 * 불변식).
 */
public record DomesticDailyOhlcvFetch(
        List<ParsedOhlcvRow> rows, LocalDate oldestTradeDate, int rowCount) {

    public DomesticDailyOhlcvFetch {
        rows = List.copyOf(rows);
    }
}
