package com.aaa.collector.stock.daily;

import java.time.LocalDate;
import java.util.List;

public record OverseasDailyOhlcvFetch(
        List<KisOverseasDailyOhlcvResponse.OverseasDailyOhlcvRow> rows,
        LocalDate oldestTradeDate,
        int rowCount) {

    public OverseasDailyOhlcvFetch {
        rows = List.copyOf(rows);
    }
}
