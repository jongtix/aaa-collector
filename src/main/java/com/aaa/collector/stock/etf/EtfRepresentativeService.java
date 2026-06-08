package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.GradeCacheRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGradeRepository;
import com.aaa.collector.stock.enums.AssetType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ETF 대표 종목 선정 서비스 (SPEC-ETF-001).
 *
 * <p>group_key 기준으로 ETF를 그룹화하고 ADTV → 상장일 → 심볼 순 tie-breaker로 대표를 선정한다. 변경 분만 stock_grades에 반영하고
 * history를 append한다.
 */
// @MX:ANCHOR: [AUTO] recalculate() — ETF 대표 선정 핵심 진입점
// @MX:REASON: fan_in >= 3 (EtfRepresentativeScheduler + 통합테스트 + 향후 수동 트리거)
@Slf4j
@Service
@RequiredArgsConstructor
public class EtfRepresentativeService {

    static final int MIN_TRADING_DAYS = 20;
    static final String GRADE_REPRESENTATIVE = "A";
    static final String GRADE_NON_REPRESENTATIVE = "C";
    static final String GRADE_F = "F";

    private final EtfMetadataRepository etfMetadataRepository;
    private final StockGradeRepository stockGradeRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final EtfRepresentativeHistoryRepository historyRepository;
    private final GradeCacheRepository gradeCacheRepository;

    /**
     * ETF 대표 종목 주간 재계산.
     *
     * <p>1. etf_metadata 전체 조회 (stock JOIN) 2. group_key 기준 메모리 groupBy 3. 그룹별 후보 필터 → ADTV 계산 → 대표
     * 선정 → 변경 분 반영
     *
     * <p>@Transactional(readOnly=false): 그룹별 처리는 개별 saveHistory/upsertGrade가 각자 트랜잭션을 가지므로 최외곽은
     * readOnly로 두어도 되나, 메서드 전체를 하나의 트랜잭션으로 묶어 일관성을 높인다.
     */
    @Transactional
    public void recalculate() {
        List<EtfMetadata> allMetadata = etfMetadataRepository.findAllWithStock();
        log.info(
                "ETF representative recalculation started — total ETF metadata rows: {}",
                allMetadata.size());

        // REQ-ETFALG-007: warn for ETF stocks missing etf_metadata
        List<Long> missingMeta = etfMetadataRepository.findStockIdsWithoutMetadata(AssetType.ETF);
        if (!missingMeta.isEmpty()) {
            log.warn(
                    "ETF stocks without etf_metadata — skipped from recalculation, grades unchanged: stockIds={}",
                    missingMeta);
        }

        // Group by group_key (market:index:leverage:direction:hedged)
        Map<String, List<EtfMetadata>> groups = groupByKey(allMetadata);
        log.info("ETF groups: {}", groups.size());

        // Collect all candidate stock IDs for batch ADTV query
        List<Long> allCandidateIds = collectAllCandidateIds(groups);
        Map<Long, Double> adtvMap = fetchAdtvMap(allCandidateIds);

        for (Map.Entry<String, List<EtfMetadata>> entry : groups.entrySet()) {
            processGroup(entry.getKey(), entry.getValue(), adtvMap);
        }

        log.info("ETF representative recalculation completed");
    }

    private Map<String, List<EtfMetadata>> groupByKey(List<EtfMetadata> allMetadata) {
        return allMetadata.stream().collect(Collectors.groupingBy(this::buildGroupKey));
    }

    private String buildGroupKey(EtfMetadata meta) {
        Stock stock = meta.getStock();
        String indexCode =
                meta.getUnderlyingIndexCode() != null ? meta.getUnderlyingIndexCode() : "UNKNOWN";
        String direction = meta.isInverse() ? "INVERSE" : "NORMAL";
        return stock.getMarket().name()
                + ":"
                + indexCode
                + ":"
                + meta.getLeverage()
                + ":"
                + direction
                + ":"
                + meta.isHedged();
    }

    private List<Long> collectAllCandidateIds(Map<String, List<EtfMetadata>> groups) {
        return groups.values().stream()
                .flatMap(List::stream)
                .map(m -> m.getStock().getId())
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<Long, Double> fetchAdtvMap(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> results = dailyOhlcvRepository.findAdtvByStockIds(stockIds);
        Map<Long, Double> map = new ConcurrentHashMap<>();
        for (Object[] row : results) {
            Long stockId = ((Number) row[0]).longValue();
            Double adtv = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            map.put(stockId, adtv);
        }
        return map;
    }

    private void processGroup(
            String groupKey, List<EtfMetadata> groupMetadata, Map<Long, Double> adtvMap) {
        // Step 1: Filter candidates
        List<EtfMetadata> candidates = filterCandidates(groupKey, groupMetadata);

        if (candidates.isEmpty()) {
            log.info("No candidates for group_key={} — skipping", groupKey);
            return;
        }

        // Step 2: Sort by ADTV desc → listedDate asc → symbol asc (tie-breaker)
        candidates.sort(buildComparator(adtvMap));

        // Step 3: Select representative (index 0) and demote rest
        EtfMetadata representative = candidates.getFirst();
        Stock representativeStock = representative.getStock();

        // Step 4: Update grades for non-representatives
        for (int i = 1; i < candidates.size(); i++) {
            Stock stock = candidates.get(i).getStock();
            upsertGradeIfChanged(stock, GRADE_NON_REPRESENTATIVE);
        }

        // Step 5: Ensure representative grade is not C (keep A if already A, but do not demote)
        // Representative keeps its current grade unless it was previously C (then restore to A)
        upsertRepresentativeGradeIfNeeded(representativeStock);

        // Step 6: Record history if representative changed
        recordHistoryIfChanged(groupKey, representativeStock);
    }

    private List<EtfMetadata> filterCandidates(String groupKey, List<EtfMetadata> groupMetadata) {
        return groupMetadata.stream()
                .filter(meta -> !isExcluded(groupKey, meta))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean isExcluded(String groupKey, EtfMetadata meta) {
        Stock stock = meta.getStock();
        // REQ-ETFALG-005: tr_stop
        if (meta.isTrStop()) {
            logExclusion(stock.getSymbol(), groupKey, "tr_stop=true");
            return true;
        }
        // REQ-ETFALG-005: watchlist_removed_at
        if (stock.getWatchlistRemovedAt() != null) {
            logExclusion(stock.getSymbol(), groupKey, "watchlist_removed_at is set");
            return true;
        }
        // REQ-ETFALG-006: < 20 valid trading days
        long tradingDays = dailyOhlcvRepository.countByStockId(stock.getId());
        if (tradingDays < MIN_TRADING_DAYS) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Excluding stock={} from group={} — only {} trading days (< {})",
                        stock.getSymbol(),
                        groupKey,
                        tradingDays,
                        MIN_TRADING_DAYS);
            }
            return true;
        }
        return false;
    }

    private void logExclusion(String symbol, String groupKey, String reason) {
        if (log.isDebugEnabled()) {
            log.debug("Excluding stock={} from group={} — {}", symbol, groupKey, reason);
        }
    }

    private Comparator<EtfMetadata> buildComparator(Map<Long, Double> adtvMap) {
        return Comparator
                // ADTV descending (higher is better)
                .<EtfMetadata, Double>comparing(
                        m -> adtvMap.getOrDefault(m.getStock().getId(), 0.0),
                        Comparator.reverseOrder())
                // Listed date ascending (earlier listed wins)
                .thenComparing(
                        m -> m.getStock().getListedDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // Symbol ascending (lexicographically earlier wins)
                .thenComparing(m -> m.getStock().getSymbol());
    }

    private void upsertGradeIfChanged(Stock stock, String newGrade) {
        stockGradeRepository.upsertGrade(stock.getId(), newGrade, LocalDateTime.now());
        gradeCacheRepository.set(stock.getSymbol(), newGrade);
        if (log.isDebugEnabled()) {
            log.debug("Grade updated — symbol={}, grade={}", stock.getSymbol(), newGrade);
        }
    }

    private void upsertRepresentativeGradeIfNeeded(Stock stock) {
        // Representative should be A grade; if previously demoted to C, restore.
        // We always upsert A for the representative to ensure correctness.
        stockGradeRepository.upsertGrade(stock.getId(), GRADE_REPRESENTATIVE, LocalDateTime.now());
        // Cache update is best-effort; representative keeps A regardless of cache failure.
        gradeCacheRepository.set(stock.getSymbol(), GRADE_REPRESENTATIVE);
        if (log.isDebugEnabled()) {
            log.debug("Representative grade ensured — symbol={}, grade=A", stock.getSymbol());
        }
    }

    private void recordHistoryIfChanged(String groupKey, Stock newRepresentative) {
        Optional<EtfRepresentativeHistory> latest =
                historyRepository.findLatestByGroupKey(groupKey);

        boolean changed =
                latest.map(h -> !h.getStock().getId().equals(newRepresentative.getId()))
                        .orElse(true); // no history yet → first selection is always recorded

        if (changed) {
            Stock prevStock = latest.map(EtfRepresentativeHistory::getStock).orElse(null);
            historyRepository.save(
                    EtfRepresentativeHistory.builder()
                            .groupKey(groupKey)
                            .stock(newRepresentative)
                            .prevStock(prevStock)
                            .effectiveFrom(LocalDateTime.now())
                            .build());
            log.info(
                    "ETF representative changed — groupKey={}, new={}, prev={}",
                    groupKey,
                    newRepresentative.getSymbol(),
                    prevStock != null ? prevStock.getSymbol() : "none");
        }
    }
}
