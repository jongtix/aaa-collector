package com.aaa.collector.stock.supply;

import com.aaa.collector.stock.CreditBalance;
import java.time.LocalDate;
import java.util.List;

public record CreditBalanceFetch(
        List<CreditBalance> rows, LocalDate oldestTradeDate, int rowCount) {

    public CreditBalanceFetch {
        rows = List.copyOf(rows);
    }
}
