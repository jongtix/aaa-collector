package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingResponse;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.Market;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종목 등급 분류 오케스트레이터.
 *
 * <p>분류 흐름:
 *
 * <ol>
 *   <li>활성 종목 조회 ({@link StockRepository#findAllActive()})
 *   <li>시장별 그룹화 후 KIS 순위 API 조회
 *   <li>시장별 ADTV 백분위 계산
 *   <li>종목별 등급 분류 ({@link GradeClassifier})
 *   <li>DB 영속화 ({@link StockGradeRepository}) + Redis 캐시 ({@link GradeCacheRepository})
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeClassificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // KRX 시장 집합
    private static final Set<Market> KRX_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ);
    // US 시장 집합
    private static final Set<Market> US_MARKETS = Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final StockRepository stockRepository;
    private final KisDomesticRankingClient domesticRankingClient;
    private final KisOverseasRankingClient overseasRankingClient;
    private final AdtvPercentileCalculator percentileCalculator;
    private final GradeClassifier gradeClassifier;
    private final StockGradePersistService stockGradePersistService;
    private final ListedYearsResolver listedYearsResolver;

    /**
     * KRX(KOSPI/KOSDAQ) 종목만 등급 분류한다.
     *
     * <p>라이브 KIS 국내 순위 fetch 후 백분위 재계산. KRX 종목 결손 시 보류(이전 등급 유지). 개별 종목 분류 실패 시 해당 종목을 skip하고 나머지를
     * 계속 처리한다.
     */
    // @MX:NOTE: [AUTO] KRX 시장 분류 진입점. 라이브 fetch→percentile→classify→persist.
    // 결손(빈 결과/예외) 시 전 KRX 종목 보류(REQ-004/006). Task 4 refactor로 classify()에서 분리.
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classifyDomestic() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // KRX 시장 백분위 계산 — Optional.empty()는 결손(보류) 의미
        Optional<Map<String, Double>> krxPercentiles = buildKrxPercentiles(allActive);

        ZonedDateTime gradedAt = ZonedDateTime.now(KST);

        for (Stock stock : allActive) {
            // KRX 종목만 처리
            if (!KRX_MARKETS.contains(stock.getMarket())) {
                continue;
            }
            // 순위 데이터 결손 시 보류 — 이전 등급 유지(REQ-004)
            if (krxPercentiles.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "KRX 순위 데이터 결손으로 분류 보류 — symbol={}, market={}",
                            stock.getSymbol(),
                            stock.getMarket());
                }
                continue;
            }
            try {
                double percentile = krxPercentiles.get().getOrDefault(stock.getSymbol(), 100.0);
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
     * <p>라이브 KIS 해외 순위 fetch 후 백분위 재계산. US 종목 결손 시 보류(이전 등급 유지). 개별 종목 분류 실패 시 해당 종목을 skip하고 나머지를
     * 계속 처리한다.
     */
    // @MX:NOTE: [AUTO] US 시장 분류 진입점. 라이브 fetch→percentile→classify→persist.
    // 결손(빈 결과/예외) 시 전 US 종목 보류(REQ-004/006). Task 4 refactor로 classify()에서 분리.
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classifyOverseas() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // US 시장 백분위 계산 — Optional.empty()는 결손(보류) 의미
        Optional<Map<String, Double>> usPercentiles = buildUsPercentiles(allActive);

        ZonedDateTime gradedAt = ZonedDateTime.now(KST);

        for (Stock stock : allActive) {
            // US 종목만 처리
            if (!US_MARKETS.contains(stock.getMarket())) {
                continue;
            }
            // 순위 데이터 결손 시 보류 — 이전 등급 유지(REQ-004)
            if (usPercentiles.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "US 순위 데이터 결손으로 분류 보류 — symbol={}, market={}",
                            stock.getSymbol(),
                            stock.getMarket());
                }
                continue;
            }
            try {
                double percentile = usPercentiles.get().getOrDefault(stock.getSymbol(), 100.0);
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

    /**
     * 모든 활성 종목의 등급을 분류한다.
     *
     * <p>개별 종목 분류 실패 시 해당 종목을 skip하고 나머지를 계속 처리한다 (REQ-009/010).
     *
     * <p>Task 5에서 {@link #classifyDomestic()} / {@link #classifyOverseas()} 호출로 대체 예정.
     */
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classify() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // 시장별 백분위 계산 — Optional.empty()는 해당 시장 순위 데이터 결손(분류 보류) 의미
        Optional<Map<String, Double>> krxPercentiles = buildKrxPercentiles(allActive);
        Optional<Map<String, Double>> usPercentiles = buildUsPercentiles(allActive);

        ZonedDateTime gradedAt = ZonedDateTime.now(KST);

        for (Stock stock : allActive) {
            // 해당 시장 순위 데이터 결손 시 분류 보류 — 이전 등급 유지(REQ-004)
            if (isWithheld(stock, krxPercentiles, usPercentiles)) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "순위 데이터 결손으로 분류 보류 — symbol={}, market={}",
                            stock.getSymbol(),
                            stock.getMarket());
                }
                continue;
            }
            try {
                Map<String, Double> krxMap = krxPercentiles.orElse(Map.of());
                Map<String, Double> usMap = usPercentiles.orElse(Map.of());
                double percentile = resolvePercentile(stock, krxMap, usMap);
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
                        "종목 등급 분류 실패 — symbol={}, market={}: {}",
                        stock.getSymbol(),
                        stock.getMarket(),
                        e.getMessage());
                // 이전 등급 유지 (REQ-009) 또는 skip (REQ-010) — persist 없이 다음 종목으로 진행
            }
        }
    }

    /**
     * 해당 종목의 시장 순위 데이터가 결손 상태인지 판단한다.
     *
     * <p>KRX/US 시장만 판정 대상. INDEX/COMMODITY 등은 결손 판정 불필요(100.0 fallback 정상 처리).
     */
    private boolean isWithheld(
            Stock stock,
            Optional<Map<String, Double>> krxPercentiles,
            Optional<Map<String, Double>> usPercentiles) {
        // INDEX/COMMODITY 등은 보류 판정 없음 — 100.0 fallback으로 정상 처리
        return (KRX_MARKETS.contains(stock.getMarket()) && krxPercentiles.isEmpty())
                || (US_MARKETS.contains(stock.getMarket()) && usPercentiles.isEmpty());
    }

    /**
     * KRX 시장 ADTV 백분위 계산.
     *
     * @return KRX 종목이 없으면 {@code Optional.of(Map.of())} (정상, 보류 아님). {@code fetchRanking()}이 빈 결과
     *     반환 또는 예외 발생 시 {@code Optional.empty()} (결손, 분류 보류).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // fetchRanking 예외 포착 — 시장별 독립 보류 처리
    private Optional<Map<String, Double>> buildKrxPercentiles(List<Stock> allActive) {
        boolean hasKrxStock = allActive.stream().anyMatch(s -> KRX_MARKETS.contains(s.getMarket()));
        if (!hasKrxStock) {
            // KRX 종목 없음 — 정상, 보류 판정 불필요
            return Optional.of(Map.of());
        }
        List<KisDomesticRankingResponse.RankedStock> ranked;
        try {
            ranked = domesticRankingClient.fetchRanking();
        } catch (Exception e) {
            log.warn("KRX 순위 조회 실패 — KRX 종목 분류 보류(REQ-004): {}", e.getMessage());
            return Optional.empty();
        }
        if (ranked.isEmpty()) {
            // @MX:NOTE: [AUTO] 순위 데이터 전체 공백 — 전 종목 C 강등 방지를 위해 분류 보류(REQ-004)
            log.warn("KRX 순위 데이터 공백 — KRX 종목 분류 보류(REQ-004)");
            return Optional.empty();
        }
        List<AdtvPercentileCalculator.RankEntry> entries =
                ranked.stream()
                        .map(
                                r ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                r.mkscShrnIscd(), parseRankAsValue(r.dataRank())))
                        .filter(e -> e.rankValue() > 0.0) // CR-004: 파싱 실패(0.0) 항목 제외
                        .toList();
        if (entries.isEmpty()) {
            // @MX:NOTE: [AUTO] 응답 공백 OR 사용 불가 데이터 — 전 종목 C 강등 방지를 위해 분류 보류(REQ-004)
            log.warn("KRX 순위 데이터 사용 불가(전체 파싱 실패) — KRX 종목 분류 보류(REQ-004)");
            return Optional.empty();
        }
        return Optional.of(percentileCalculator.calculate(entries));
    }

    /**
     * US 시장 ADTV 백분위 계산.
     *
     * @return US 종목이 없으면 {@code Optional.of(Map.of())} (정상, 보류 아님). {@code fetchRanking()}이 빈 결과 반환
     *     또는 예외 발생 시 {@code Optional.empty()} (결손, 분류 보류).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // fetchRanking 예외 포착 — 시장별 독립 보류 처리
    private Optional<Map<String, Double>> buildUsPercentiles(List<Stock> allActive) {
        boolean hasUsStock = allActive.stream().anyMatch(s -> US_MARKETS.contains(s.getMarket()));
        if (!hasUsStock) {
            // US 종목 없음 — 정상, 보류 판정 불필요
            return Optional.of(Map.of());
        }
        List<KisOverseasRankingResponse.RankedStock> ranked;
        try {
            ranked = overseasRankingClient.fetchRanking();
        } catch (Exception e) {
            log.warn("US 순위 조회 실패 — US 종목 분류 보류(REQ-004): {}", e.getMessage());
            return Optional.empty();
        }
        if (ranked.isEmpty()) {
            log.warn("US 순위 데이터 공백 — US 종목 분류 보류(REQ-004)");
            return Optional.empty();
        }
        // 해외는 이미 rank 순서로 반환 — rankValue를 역수(높은 랭크 = 낮은 값)로 변환해서 순위 역산
        List<AdtvPercentileCalculator.RankEntry> entries =
                ranked.stream()
                        .map(
                                r ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                r.symb(), invertRank(r.rank(), ranked.size())))
                        .filter(e -> e.rankValue() > 0.0) // 파싱 실패(0.0) 항목 제외 — KRX와 동일 처리
                        .toList();
        if (entries.isEmpty()) {
            // @MX:NOTE: [AUTO] 응답 공백 OR 사용 불가 데이터 — 전 종목 C 강등 방지를 위해 분류 보류(REQ-004)
            log.warn("US 순위 데이터 사용 불가(전체 파싱 실패) — US 종목 분류 보류(REQ-004)");
            return Optional.empty();
        }
        return Optional.of(percentileCalculator.calculate(entries));
    }

    private double resolvePercentile(
            Stock stock, Map<String, Double> krxPercentiles, Map<String, Double> usPercentiles) {
        if (KRX_MARKETS.contains(stock.getMarket())) {
            return krxPercentiles.getOrDefault(stock.getSymbol(), 100.0);
        }
        if (US_MARKETS.contains(stock.getMarket())) {
            return usPercentiles.getOrDefault(stock.getSymbol(), 100.0);
        }
        // INDEX/COMMODITY 등은 등급 분류 대상이 아님 → 100으로 처리(C 등급)
        return 100.0;
    }

    /** 순위 문자열을 double로 변환. 파싱 실패 시 큰 값(하위) 반환. */
    private double parseRankAsValue(String rank) {
        try {
            // rank 숫자가 작을수록 상위 → 역수를 사용하여 "높은 값 = 상위" 변환
            int rankInt = Integer.parseInt(rank.trim());
            return 1.0 / rankInt;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 해외 rank 문자열을 inverted value로 변환. */
    private double invertRank(String rank, int total) {
        try {
            int rankInt = Integer.parseInt(rank.trim());
            return total - rankInt + 1.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
