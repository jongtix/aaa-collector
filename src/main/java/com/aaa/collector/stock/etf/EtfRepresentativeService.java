package com.aaa.collector.stock.etf;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.grade.Grade;
import com.aaa.collector.stock.grade.GradeCacheRepository;
import com.aaa.collector.stock.grade.GradeClassifier;
import com.aaa.collector.stock.grade.GradeInput;
import com.aaa.collector.stock.grade.StockGradeRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    static final Grade GRADE_NON_REPRESENTATIVE = Grade.C;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EtfMetadataRepository etfMetadataRepository;
    private final StockGradeRepository stockGradeRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final EtfRepresentativeHistoryRepository historyRepository;
    private final GradeCacheRepository gradeCacheRepository;
    private final GradeClassifier gradeClassifier;

    /**
     * ETF 대표 종목 주간 재계산.
     *
     * <p>1. etf_metadata 전체 조회 (stock JOIN) 2. group_key 기준 메모리 groupBy 3. 그룹별 후보 필터 → ADTV 계산 → 대표
     * 선정 → 변경 분 반영
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

        Map<String, List<EtfMetadata>> groups = groupByKey(allMetadata);
        log.info("ETF groups: {}", groups.size());

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
        List<EtfMetadata> candidates = filterCandidates(groupKey, groupMetadata);

        if (candidates.isEmpty()) {
            log.info("No candidates for group_key={} — skipping", groupKey);
            return;
        }

        candidates.sort(buildComparator(adtvMap));

        EtfMetadata representative = candidates.getFirst();
        Stock representativeStock = representative.getStock();

        for (int i = 1; i < candidates.size(); i++) {
            Stock stock = candidates.get(i).getStock();
            upsertGrade(stock, GRADE_NON_REPRESENTATIVE);
        }

        // 대표 ETF: 무조건 A가 아닌 holdingDays + ADTV로 A/B/C 산정 (REQ-GRADE4-031, 032)
        // GradeClassifier의 ETF→C 분기를 우회하기 위해 assetType을 STOCK으로 전달
        long holdingDays = dailyOhlcvRepository.countByStockId(representativeStock.getId());

        // 분류용 ADTV: 선정 비교자(findAdtvByStockIds, 전체-행 AVG)와 별개로 20일 쿼리 사용 (REQ-GRADE4-031)
        List<Object[]> classificationAdtvRows =
                dailyOhlcvRepository.findRecent20DayAdtvByStockIds(
                        List.of(representativeStock.getId()));
        double adtv =
                classificationAdtvRows.isEmpty()
                        ? 0.0
                        : ((Number) classificationAdtvRows.getFirst()[1]).doubleValue();

        String market = representativeStock.getMarket().name().startsWith("KOS") ? "KRX" : "US";
        GradeInput input =
                new GradeInput(
                        representativeStock.getSymbol(),
                        representativeStock.getNameKo(),
                        AssetType.STOCK, // ETF 분기 우회 — 대표 ETF는 holdingDays+ADTV로 A/B/C 판정
                        holdingDays,
                        adtv,
                        market);
        Grade representativeGrade = gradeClassifier.classify(input);
        upsertGrade(representativeStock, representativeGrade);
        recordHistoryIfChanged(groupKey, representativeStock);
    }

    private List<EtfMetadata> filterCandidates(String groupKey, List<EtfMetadata> groupMetadata) {
        return groupMetadata.stream()
                .filter(meta -> !isExcluded(groupKey, meta))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean isExcluded(String groupKey, EtfMetadata meta) {
        Stock stock = meta.getStock();
        if (meta.isTrStop()) {
            logExclusion(stock.getSymbol(), groupKey, "tr_stop=true");
            return true;
        }
        if (stock.getWatchlistRemovedAt() != null) {
            logExclusion(stock.getSymbol(), groupKey, "watchlist_removed_at is set");
            return true;
        }
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
        return Comparator.<EtfMetadata, Double>comparing(
                        m -> adtvMap.getOrDefault(m.getStock().getId(), 0.0),
                        Comparator.reverseOrder())
                .thenComparing(
                        m -> m.getStock().getListedDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(m -> m.getStock().getSymbol());
    }

    private void upsertGrade(Stock stock, Grade grade) {
        ZonedDateTime now = ZonedDateTime.now(KST);
        Optional<StockGrade> existing = stockGradeRepository.findByStock(stock);
        if (existing.isPresent()) {
            existing.get().updateGrade(grade.name(), now);
        } else {
            stockGradeRepository.save(
                    StockGrade.builder().stock(stock).grade(grade.name()).gradedAt(now).build());
        }
        gradeCacheRepository.save(stock.getSymbol(), grade, now);
        if (log.isDebugEnabled()) {
            log.debug("Grade upserted — symbol={}, grade={}", stock.getSymbol(), grade);
        }
    }

    private void recordHistoryIfChanged(String groupKey, Stock newRepresentative) {
        Optional<EtfRepresentativeHistory> latest =
                historyRepository.findLatestByGroupKey(groupKey);

        boolean changed =
                latest.map(h -> !h.getStock().getId().equals(newRepresentative.getId()))
                        .orElse(true);

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
