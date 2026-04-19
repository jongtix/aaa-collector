package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.Market;

record ResolvedStock(String symbol, String nameKo, Market market, StockInfo stockInfo) {}
