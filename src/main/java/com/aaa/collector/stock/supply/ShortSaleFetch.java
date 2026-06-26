package com.aaa.collector.stock.supply;

import com.aaa.collector.stock.ShortSaleDomestic;
import java.time.LocalDate;
import java.util.List;

public record ShortSaleFetch(
        List<ShortSaleDomestic> rows, LocalDate oldestTradeDate, int rowCount) {

    public ShortSaleFetch {
        rows = List.copyOf(rows);
    }
}
