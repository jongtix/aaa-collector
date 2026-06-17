package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.snapshot.RankingSnapshotService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종목 등급 분류 오케스트레이터 (SPEC-COLLECTOR-GRADE-003 v2.0.0).
 *
 * <p>분류 흐름:
 *
 * <ol>
 *   <li>활성 종목 조회 ({@link StockRepository#findAllActive()})
 *   <li>당주기 스냅샷 조회 ({@link RankingSnapshotService#findByMarketAndDate(String, LocalDate)})
 *   <li>시장 단위 freshness 판정 — 스냅샷 부재 시 보류(REQ-GRADE-006)
 *   <li>시장별 ADTV 백분위 재계산
 *   <li>종목별 등급 분류 ({@link GradeClassifier})
 *   <li>DB 영속화 ({@link StockGradePersistService})
 * </ol>
 *
 * <p>라이브 fetch 대신 스냅샷 읽기 기반 percentile 재계산으로 전환(Task 4 feat).
 */
// @MX:NOTE: [AUTO] 라이브 fetch 아닌 스냅샷 읽어 percentile 재계산.
// 당주기 스냅샷 부재(KRX=KST, US=ET) 시 보류(이전 등급 유지, REQ-GRADE-006).
// classifyDomestic/classifyOverseas 독립 진입점 — WatchlistSyncScheduler에서 각각 호출.
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeClassificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // KRX 시장 집합
    private static final Set<Market> KRX_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ);
    // US 시장 집합
    private static final Set<Market> US_MARKETS = Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final StockRepository stockRepository;
    private final RankingSnapshotService snapshotService;
    private final AdtvPercentileCalculator percentileCalculator;
    private final GradeClassifier gradeClassifier;
    private final StockGradePersistService stockGradePersistService;
    private final ListedYearsResolver listedYearsResolver;

    // 테스트 주입 가능한 시계 (기본: 시스템 시계)
    private Clock clock = Clock.systemDefaultZone();

    /**
     * KRX(KOSPI/KOSDAQ) 종목만 등급 분류한다.
     *
     * <p>당일 KST 날짜의 KRX 스냅샷 읽어 percentile 재계산. 스냅샷 부재(empty) 시 전 KRX 종목 보류 — C 강등 없음(이전 등급 유지,
     * REQ-GRADE-006). 개별 종목 분류 실패 시 해당 종목만 skip.
     */
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classifyDomestic() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // KRX 신선도 판정: 당일 KST 날짜 스냅샷 조회
        LocalDate krxToday = LocalDate.now(clock.withZone(KST));
        List<com.aaa.collector.stock.grade.snapshot.RankingSnapshot> krxSnapshots =
                snapshotService.findByMarketAndDate("KRX", krxToday);

        // 스냅샷 부재 시 전 KRX 종목 보류 — 이전 등급 유지(REQ-GRADE-006)
        if (krxSnapshots.isEmpty()) {
            log.warn("KRX 당주기 스냅샷 부재(date={}) — KRX 종목 분류 보류(REQ-GRADE-006)", krxToday);
            return;
        }

        // 스냅샷 → RankEntry → percentile 재계산
        List<AdtvPercentileCalculator.RankEntry> entries =
                krxSnapshots.stream()
                        .map(
                                s ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                s.getSymbol(), s.getRankValue()))
                        .toList();
        Map<String, Double> krxPercentiles = percentileCalculator.calculate(entries);

        ZonedDateTime gradedAt = ZonedDateTime.now(clock.withZone(KST));

        for (Stock stock : allActive) {
            if (!KRX_MARKETS.contains(stock.getMarket())) {
                continue;
            }
            try {
                double percentile = krxPercentiles.getOrDefault(stock.getSymbol(), 100.0);
                double listedYears = listedYearsResolver.resolve(stock);
                GradeInput input =
                        new GradeInput(
                                stock.getSymbol(),
                                stock.getNameKo(),
                                stock.getAssetType(),
                                listedYears,
                                percentile);
                Grade grade = gradeClassifier.classify(input);
                stockGradePersistService.persistSingle(stock, grade, gradedAt);
            } catch (Exception e) {
                log.warn(
                        "KRX 종목 등급 분류 실패 — symbol={}, market={}: {}",
                        stock.getSymbol(),
                        stock.getMarket(),
                        e.getMessage());
            }
        }
    }

    /**
     * US(NYSE/NASDAQ/AMEX) 종목만 등급 분류한다.
     *
     * <p>당일 ET 날짜의 US 스냅샷 읽어 percentile 재계산. 스냅샷 부재(empty) 시 전 US 종목 보류 — C 강등 없음(이전 등급 유지,
     * REQ-GRADE-006). 개별 종목 분류 실패 시 해당 종목만 skip.
     */
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classifyOverseas() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // US 신선도 판정: 당일 ET 날짜 스냅샷 조회 (EDT/EST 양 시즌 안전)
        LocalDate usToday = LocalDate.now(clock.withZone(ET));
        List<com.aaa.collector.stock.grade.snapshot.RankingSnapshot> usSnapshots =
                snapshotService.findByMarketAndDate("US", usToday);

        // 스냅샷 부재 시 전 US 종목 보류 — 이전 등급 유지(REQ-GRADE-006)
        if (usSnapshots.isEmpty()) {
            log.warn("US 당주기 스냅샷 부재(date={}) — US 종목 분류 보류(REQ-GRADE-006)", usToday);
            return;
        }

        // 스냅샷 → RankEntry → percentile 재계산
        List<AdtvPercentileCalculator.RankEntry> entries =
                usSnapshots.stream()
                        .map(
                                s ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                s.getSymbol(), s.getRankValue()))
                        .toList();
        Map<String, Double> usPercentiles = percentileCalculator.calculate(entries);

        ZonedDateTime gradedAt = ZonedDateTime.now(clock.withZone(ET));

        for (Stock stock : allActive) {
            if (!US_MARKETS.contains(stock.getMarket())) {
                continue;
            }
            try {
                double percentile = usPercentiles.getOrDefault(stock.getSymbol(), 100.0);
                double listedYears = listedYearsResolver.resolve(stock);
                GradeInput input =
                        new GradeInput(
                                stock.getSymbol(),
                                stock.getNameKo(),
                                stock.getAssetType(),
                                listedYears,
                                percentile);
                Grade grade = gradeClassifier.classify(input);
                stockGradePersistService.persistSingle(stock, grade, gradedAt);
            } catch (Exception e) {
                log.warn(
                        "US 종목 등급 분류 실패 — symbol={}, market={}: {}",
                        stock.getSymbol(),
                        stock.getMarket(),
                        e.getMessage());
            }
        }
    }
}
