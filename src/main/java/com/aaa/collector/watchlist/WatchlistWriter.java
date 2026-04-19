package com.aaa.collector.watchlist;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관심종목 데이터를 {@code stocks} 테이블에 upsert하는 서비스. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistWriter {

    private final StockRepository stockRepository;

    /** 수집된 종목 목록을 {@code stocks} 테이블에 upsert한다. */
    @Transactional
    public void upsertAll(List<ResolvedStock> stocks) {
        if (stocks.isEmpty()) {
            return;
        }

        Map<String, Stock> existingByKey = loadExisting(stocks);
        Counter counter = new Counter();
        Set<Long> touchedIds = new HashSet<>();
        List<Stock> toInsert = new ArrayList<>();

        for (ResolvedStock resolved : stocks) {
            processEntry(resolved, existingByKey, counter, touchedIds, toInsert);
        }

        if (!toInsert.isEmpty()) {
            stockRepository.saveAll(toInsert);
        }

        Set<Long> removedIds =
                existingByKey.values().stream()
                        .map(Stock::getId)
                        .filter(id -> !touchedIds.contains(id))
                        .collect(Collectors.toSet());
        if (!removedIds.isEmpty()) {
            stockRepository.markWatchlistRemoved(removedIds);
            counter.removed += removedIds.size();
        }

        log.info(
                "관심종목 동기화 완료 — inserted={}, updated={}, removed={}, unchanged={}",
                counter.inserted,
                counter.updated,
                counter.removed,
                counter.unchanged);
    }

    private Map<String, Stock> loadExisting(List<ResolvedStock> stocks) {
        Set<String> symbols =
                stocks.stream().map(ResolvedStock::symbol).collect(Collectors.toSet());
        return stockRepository.findAllBySymbolIn(symbols).stream()
                .collect(
                        Collectors.toMap(
                                s -> s.getSymbol() + ":" + s.getMarket().name(),
                                Function.identity()));
    }

    private void processEntry(
            ResolvedStock resolved,
            Map<String, Stock> existingByKey,
            Counter counter,
            Set<Long> touchedIds,
            List<Stock> toInsert) {
        Stock existing = existingByKey.get(resolved.symbol() + ":" + resolved.market().name());
        if (existing == null) {
            toInsert.add(buildStock(resolved));
            counter.inserted++;
        } else {
            touchedIds.add(existing.getId());
            updateIfNeeded(existing, resolved, counter);
        }
    }

    private Stock buildStock(ResolvedStock resolved) {
        StockInfo info = resolved.stockInfo();
        return Stock.builder()
                .symbol(resolved.symbol())
                .nameKo(resolved.nameKo())
                .nameEn(info != null ? info.nameEn() : null)
                .market(resolved.market())
                .assetType(info != null ? info.assetType() : AssetType.STOCK)
                .listedDate(info != null ? info.listedDate() : null)
                .active(true)
                .build();
    }

    private void updateIfNeeded(Stock existing, ResolvedStock resolved, Counter counter) {
        StockInfo info = resolved.stockInfo();
        String nameEn = info != null ? info.nameEn() : null;
        if (existing.updateNames(resolved.nameKo(), nameEn)) {
            counter.updated++;
        } else {
            counter.unchanged++;
        }
    }

    private static final class Counter {
        int inserted;
        int updated;
        int removed;
        int unchanged;
    }
}
