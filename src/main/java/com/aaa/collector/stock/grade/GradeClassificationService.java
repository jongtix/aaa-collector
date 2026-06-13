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

    // 상장일 미상 종목의 fallback 경과 연수. A 등급 임계(7년)를 충분히 초과하는 값으로,
    // KIS가 상장일을 제공하지 않는 종목(예: 장기 상장 해외 블루칩)을 장기 상장으로 간주한다.
    private static final double ESTABLISHED_FALLBACK_YEARS = 100.0;

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
    // @MX:NOTE: [AUTO] WatchlistSyncService.sync() 말미 단일 호출 진입점.
    // failedGroupCount와 무관하게 항상 실행, 예외는 호출자 try/catch로 격리(SPEC-COLLECTOR-GRADE-002).
    // 개별 종목 실패는 warn 후 계속 처리(REQ-009/010). @Transactional 없음 — KIS API 호출과 DB 트랜잭션 분리(CR-002).
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

    private double resolveListedYears(Stock stock) {
        LocalDate listedDate = stock.getListedDate();
        if (listedDate == null) {
            // KIS가 상장일을 제공하지 않는 종목(예: 장기 상장 해외 블루칩 JPM·GS)은 장기 상장으로 간주한다.
            // 단, 신규 상장 종목이 상장일 누락으로 잘못 A 등급 후보가 될 위험이 있으므로(고거래량 IPO 등),
            // fallback 적용 종목은 반드시 식별 가능하게 로깅하여 예상 밖 종목을 조기에 포착한다.
            log.warn(
                    "상장일 미상 — 장기 상장 종목으로 간주(A 등급 후보), 신규 종목 오분류 여부 확인 요망"
                            + " — symbol={}, market={}, assetType={}",
                    stock.getSymbol(),
                    stock.getMarket(),
                    stock.getAssetType());
            return ESTABLISHED_FALLBACK_YEARS;
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
