package com.aaa.collector.watchlist;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.etf.EtfMetadataWriter;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관심종목 단건 upsert. 종목별 독립 트랜잭션으로 실행되어 실패 시 해당 종목만 롤백된다. WatchlistWriter의 self-invocation 문제를 피하기 위해
 * 별도 빈으로 분리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WatchlistEntryWriter {

    private final StockRepository stockRepository;
    private final EtfMetadataWriter etfMetadataWriter;

    /**
     * 종목 1건을 upsert한다.
     *
     * <p>ETF 종목이고 etfMetaInfo가 있으면 etf_metadata도 upsert한다 (REQ-ETFMETA-002).
     *
     * @return 기존 종목의 ID (신규 INSERT 시 null)
     */
    @Transactional
    Long upsertOne(
            ResolvedStock resolved,
            Map<String, Stock> existingByKey,
            WatchlistWriter.Counter counter) {
        Stock existing = existingByKey.get(resolved.symbol() + ":" + resolved.market().name());

        Stock savedStock;
        if (existing == null) {
            savedStock = stockRepository.save(buildStock(resolved));
            counter.inserted++;
        } else {
            updateIfNeeded(existing, resolved, counter);
            savedStock = existing;
        }

        // ETF 메타데이터 upsert (REQ-ETFMETA-002)
        StockInfo info = resolved.stockInfo();
        if (info != null && info.assetType() == AssetType.ETF && info.etfMetaInfo() != null) {
            etfMetadataWriter.upsert(savedStock, info.etfMetaInfo());
        }

        return existing != null ? existing.getId() : null;
    }

    // @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
    private Stock buildStock(ResolvedStock resolved) {
        StockInfo info = resolved.stockInfo();
        Stock stock =
                Stock.builder()
                        .symbol(resolved.symbol())
                        .nameKo(resolved.nameKo())
                        .nameEn(info != null ? info.nameEn() : null)
                        .market(resolved.market())
                        .assetType(info != null ? info.assetType() : AssetType.STOCK)
                        .listedDate(info != null ? info.listedDate() : null)
                        .active(true)
                        .build();

        // 상폐/거래정지 상태 반영 (REQ-WLSYNC-147) — 빌더 파라미터를 늘리지 않고, active=true 기본 빌드 직후
        // save() 전에 UPDATE 경로와 동일한 상태 전이 메서드를 1회 호출해 INSERT·UPDATE 두 경로를 단일 불변식
        // 메서드로 수렴시킨다. info==null(신규 종목·조회 실패)이면 직전 상태가 없으므로 호출하지 않고
        // active=true 기본값 그대로 INSERT한다(REQ-WLSYNC-150).
        if (info != null) {
            stock.reflectListingStatus(info.listingStatus(), info.delistedAt());
        }
        return stock;
    }

    // @MX:SPEC: SPEC-COLLECTOR-STOCKMETA-001, SPEC-COLLECTOR-WLSYNC-008
    private void updateIfNeeded(
            Stock existing, ResolvedStock resolved, WatchlistWriter.Counter counter) {
        StockInfo info = resolved.stockInfo();
        String nameEn = info != null ? info.nameEn() : null;
        boolean changed = existing.syncFromWatchlist(resolved.nameKo(), nameEn);

        // 시장 교정 + 상장일 채우기 (REQ-STOCKMETA-004,011,012) + 상폐/거래정지 상태 반영 (REQ-WLSYNC-144~147).
        // StockInfo가 있을 때만 수행. null이면 조회 실패(graceful skip) — 상폐/거래정지 판정을 내리지 않고
        // 직전 active/delistedAt 상태를 그대로 유지한다(REQ-WLSYNC-150).
        if (info != null) {
            changed |= existing.correctMetadata(info.market(), info.listedDate());
            changed |= existing.reflectListingStatus(info.listingStatus(), info.delistedAt());
        }

        if (changed) {
            counter.updated++;
        } else {
            counter.unchanged++;
        }
    }
}
