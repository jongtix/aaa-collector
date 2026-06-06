package com.aaa.collector.watchlist;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심종목 단건 upsert. 종목별 독립 트랜잭션으로 실행되어 실패 시 해당 종목만 롤백된다. WatchlistWriter의 self-invocation 문제를 피하기 위해
 * 별도 빈으로 분리.
 */
@Service
@RequiredArgsConstructor
class WatchlistEntryWriter {

    private final StockRepository stockRepository;

    /**
     * 종목 1건을 upsert한다.
     *
     * @return 기존 종목의 ID (신규 INSERT 시 null)
     */
    @Transactional
    Long upsertOne(
            ResolvedStock resolved,
            Map<String, Stock> existingByKey,
            WatchlistWriter.Counter counter) {
        Stock existing = existingByKey.get(resolved.symbol() + ":" + resolved.market().name());
        if (existing == null) {
            // saveAll 대신 save — 종목 단위 트랜잭션 격리로 실패 시 해당 종목만 롤백 (ADR-022 결정 3)
            stockRepository.save(buildStock(resolved));
            counter.inserted++;
            return null;
        } else {
            updateIfNeeded(existing, resolved, counter);
            return existing.getId();
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

    private void updateIfNeeded(
            Stock existing, ResolvedStock resolved, WatchlistWriter.Counter counter) {
        StockInfo info = resolved.stockInfo();
        String nameEn = info != null ? info.nameEn() : null;
        if (existing.syncFromWatchlist(resolved.nameKo(), nameEn)) {
            counter.updated++;
        } else {
            counter.unchanged++;
        }
    }
}
