package com.aaa.collector.stock.daily;

import java.time.LocalDate;
import java.util.List;

public record DomesticDailyOhlcvFetch(
        List<KisDailyOhlcvResponse.DailyOhlcvRow> rows, LocalDate oldestTradeDate, int rowCount) {

    public DomesticDailyOhlcvFetch {
        rows = List.copyOf(rows);
    }
}
