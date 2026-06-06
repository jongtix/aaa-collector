package com.aaa.collector.watchlist;

import com.aaa.collector.stock.CachedStock;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListCacheRepository;
import com.aaa.collector.stock.StockRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistWriter {

    private final StockRepository stockRepository;
    private final WatchlistEntryWriter entryWriter;
    private final StockListCacheRepository stockListCacheRepository;

    /** 수집된 종목 목록을 {@code stocks} 테이블에 upsert한다. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 캐시 갱신 예외를 포착해 sync 계속 진행
    public void upsertAll(List<ResolvedStock> stocks, int failedGroupCount) {
        if (stocks.isEmpty()) {
            return;
        }

        Map<String, Stock> existingByKey = loadExisting(stocks);
        Counter counter = new Counter();
        Set<Long> touchedIds = new HashSet<>();
        Set<String> failedKeys = new HashSet<>();

        for (ResolvedStock resolved : stocks) {
            String key = resolved.symbol() + ":" + resolved.market().name();
            try {
                Long touchedId = entryWriter.upsertOne(resolved, existingByKey, counter);
                if (touchedId != null) {
                    touchedIds.add(touchedId);
                }
            } catch (DataAccessException e) {
                // ADR-022 결정 3: 실패 종목은 DB를 건드리지 않음 — skip 후 다음 동기화 주기에 재시도
                log.warn(
                        "종목 upsert 실패, skip — symbol={}, market={}",
                        resolved.symbol(),
                        resolved.market(),
                        e);
                failedKeys.add(key);
            }
        }

        // 실패 종목의 기존 DB ID도 touched 처리 → watchlist_removed_at 오인 마킹 방지
        failedKeys.stream()
                .map(existingByKey::get)
                .filter(Objects::nonNull)
                .map(Stock::getId)
                .forEach(touchedIds::add);

        if (failedGroupCount > 0) {
            log.warn("그룹 조회 {}건 실패 — markRemoved skip, 다음 sync 주기로 미룸", failedGroupCount);
        } else {
            markRemoved(existingByKey, touchedIds, counter);
        }

        log.info(
                "관심종목 동기화 완료 — inserted={}, updated={}, removed={}, unchanged={}",
                counter.inserted,
                counter.updated,
                counter.removed,
                counter.unchanged);

        // @MX:NOTE: [AUTO] 캐시 갱신 조건(failedGroupCount==0)이 markRemoved와 동일
        if (failedGroupCount == 0) {
            try {
                List<CachedStock> cached =
                        stockRepository.findAllActive().stream().map(CachedStock::from).toList();
                stockListCacheRepository.save(cached);
            } catch (Exception e) {
                log.warn("관심종목 캐시 갱신 실패 — sync 계속 진행", e);
            }
        }
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

    private void markRemoved(
            Map<String, Stock> existingByKey, Set<Long> touchedIds, Counter counter) {
        Set<Long> removedIds =
                existingByKey.values().stream()
                        .map(Stock::getId)
                        .filter(id -> !touchedIds.contains(id))
                        .collect(Collectors.toSet());
        if (!removedIds.isEmpty()) {
            stockRepository.markWatchlistRemoved(removedIds);
            counter.removed += removedIds.size();
        }
    }

    static final class Counter {
        int inserted;
        int updated;
        int removed;
        int unchanged;
    }
}
