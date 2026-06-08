package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingResponse;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

    /**
     * 모든 활성 종목의 등급을 분류한다.
     *
     * <p>개별 종목 분류 실패 시 해당 종목을 skip하고 나머지를 계속 처리한다 (REQ-009/010).
     */
    // @MX:NOTE: [AUTO] WatchlistWriter(failedGroupCount==0) 단일 호출 진입점. 개별 종목 실패는 warn 후 계속
    // 처리(REQ-009/010). @Transactional 없음 — KIS API 호출과 DB 트랜잭션 분리(CR-002).
    @SuppressWarnings({
        "PMD.AvoidCatchingGenericException", // 종목별 실패 포착해 나머지 계속 처리
        "PMD.AvoidInstantiatingObjectsInLoops" // GradeInput은 종목별 불변 값 객체로 루프 내 생성 불가피
    })
    public void classify() {
        List<Stock> allActive = stockRepository.findAllActive();
        if (allActive.isEmpty()) {
            return;
        }

        // 시장별 백분위 계산
        Map<String, Double> krxPercentiles = buildKrxPercentiles(allActive);
        Map<String, Double> usPercentiles = buildUsPercentiles(allActive);

        ZonedDateTime gradedAt = ZonedDateTime.now(KST);

        for (Stock stock : allActive) {
            try {
                double percentile = resolvePercentile(stock, krxPercentiles, usPercentiles);
                double listedYears = resolveListedYears(stock);
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

    private Map<String, Double> buildKrxPercentiles(List<Stock> allActive) {
        boolean hasKrxStock = allActive.stream().anyMatch(s -> KRX_MARKETS.contains(s.getMarket()));
        if (!hasKrxStock) {
            return Map.of();
        }
        List<KisDomesticRankingResponse.RankedStock> ranked = domesticRankingClient.fetchRanking();
        List<AdtvPercentileCalculator.RankEntry> entries =
                ranked.stream()
                        .map(
                                r ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                r.mkscShrnIscd(), parseRankAsValue(r.dataRank())))
                        .filter(e -> e.rankValue() > 0.0) // CR-004: 파싱 실패(0.0) 항목 제외
                        .toList();
        return percentileCalculator.calculate(entries);
    }

    private Map<String, Double> buildUsPercentiles(List<Stock> allActive) {
        boolean hasUsStock = allActive.stream().anyMatch(s -> US_MARKETS.contains(s.getMarket()));
        if (!hasUsStock) {
            return Map.of();
        }
        List<KisOverseasRankingResponse.RankedStock> ranked = overseasRankingClient.fetchRanking();
        // 해외는 이미 rank 순서로 반환 — rankValue를 역수(높은 랭크 = 낮은 값)로 변환해서 순위 역산
        List<AdtvPercentileCalculator.RankEntry> entries =
                ranked.stream()
                        .map(
                                r ->
                                        new AdtvPercentileCalculator.RankEntry(
                                                r.symb(), invertRank(r.rank(), ranked.size())))
                        .toList();
        return percentileCalculator.calculate(entries);
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

    private double resolveListedYears(Stock stock) {
        LocalDate listedDate = stock.getListedDate();
        if (listedDate == null) {
            throw new IllegalStateException("listedDate가 null — symbol=" + stock.getSymbol());
        }
        return ChronoUnit.DAYS.between(listedDate, LocalDate.now(KST)) / 365.25;
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
