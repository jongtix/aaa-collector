package com.aaa.collector.stock.supply;

import com.aaa.collector.stock.InvestorTrend;
import java.time.LocalDate;
import java.util.List;

public record InvestorTrendFetch(
        List<InvestorTrend> rows, LocalDate oldestTradeDate, int rowCount) {

    public InvestorTrendFetch {
        rows = List.copyOf(rows);
    }
}
