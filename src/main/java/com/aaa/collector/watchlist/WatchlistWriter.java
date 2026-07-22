package com.aaa.collector.watchlist;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListService;
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
    private final StockListService stockListService;

    /** 수집된 종목 목록을 {@code stocks} 테이블에 upsert한다. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 캐시 갱신 예외를 포착해 sync 계속 진행
    public void upsertAll(List<ResolvedStock> stocks, int failedGroupCount) {
        if (stocks.isEmpty()) {
            return;
        }

        Map<String, Stock> existingByKey = loadExisting(stocks);
        // REQ-WLSYNC-159,160: markRemoved 후보 기준집합을 업서트 루프 시작 전 스냅샷으로 로드한다 —
        // 이번 사이클에 신규 INSERT된 종목은 이 스냅샷에 없으므로 제거 후보에서 자동 제외된다.
        Set<Long> nonRemovedBase = stockRepository.findIdsByWatchlistRemovedAtIsNull();
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
            markRemoved(nonRemovedBase, touchedIds, counter);
        }

        log.info(
                "관심종목 동기화 완료 — inserted={}, updated={}, removed={}, unchanged={}",
                counter.inserted,
                counter.updated,
                counter.removed,
                counter.unchanged);

        // @MX:NOTE: [AUTO] refreshCache 갱신 조건(failedGroupCount==0)이 markRemoved와 동일.
        // classify는 SPEC-COLLECTOR-GRADE-002로 WatchlistSyncService.sync() 오케스트레이터로 이동.
        if (failedGroupCount == 0) {
            try {
                stockListService.refreshCache();
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

    /**
     * 제거 후보(기준집합 − touched)를 산정해 상한 캡 이내면 전량 마킹한다 (REQ-WLSYNC-159~162).
     *
     * <p>{@code nonRemovedBase}는 인플로우 한정이 아니라 DB측 {@code watchlist_removed_at IS NULL} 전체 행이다 —
     * 관심그룹에서 완전히 이탈해 애초에 인플로우로 로드되지 않는 종목도 후보에 오를 수 있다(REQ-WLSYNC-159).
     */
    // @MX:NOTE: [AUTO] 대량 제거 후보 시 all-or-nothing skip — 캡 max(base 5% 내림, 3), REQ-WLSYNC-161
    private void markRemoved(Set<Long> nonRemovedBase, Set<Long> touchedIds, Counter counter) {
        Set<Long> candidates =
                nonRemovedBase.stream()
                        .filter(id -> !touchedIds.contains(id))
                        .collect(Collectors.toSet());
        if (candidates.isEmpty()) {
            return;
        }

        int cap = Math.max((int) (nonRemovedBase.size() * 0.05), 3);
        if (candidates.size() > cap) {
            // all-or-nothing — 캡 초과 시 이번 사이클엔 어떤 종목도 마킹하지 않는다(REQ-WLSYNC-161)
            log.warn(
                    "제거 후보 상한 초과 — 이번 사이클 markRemoved 전체 skip: candidates={}, base={}, cap={}",
                    candidates.size(),
                    nonRemovedBase.size(),
                    cap);
            return;
        }

        stockRepository.markWatchlistRemoved(candidates);
        counter.removed += candidates.size();
    }

    static final class Counter {
        int inserted;
        int updated;
        int removed;
        int unchanged;
    }
}
