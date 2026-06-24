package com.aaa.collector.stock.grade;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.Market;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종목 등급 분류 오케스트레이터 (SPEC-COLLECTOR-GRADE-004).
 *
 * <p>분류 흐름:
 *
 * <ol>
 *   <li>활성 종목 조회 ({@link StockRepository#findAllActive()})
 *   <li>시장 필터(KRX/US) 적용
 *   <li>종목 ID 목록으로 holdingDays({@link DailyOhlcvRepository#countByStockId}) + 최근 20거래일 ADTV 배치 조회
 *   <li>종목별 GradeClassifier.classify 호출
 *   <li>DB 영속화 ({@link StockGradePersistService#persistSingle})
 * </ol>
 *
 * <p>일봉 결손(0행) 종목은 C로 분류한다 (보류 아님, REQ-GRADE4-023).
 *
 * <p>classifyDomestic()/classifyOverseas() 시그니처 유지 — WatchlistSyncScheduler 등 기존 호출처 유지.
 */
// @MX:NOTE: [AUTO] 스냅샷/percentile/ListedYearsResolver 의존 완전 제거.
// holdingDays(countByStockId) + ADTV(findRecent20DayAdtvByStockIds) 2축 모델로 전환.
@Slf4j
@Service
@RequiredArgsConstructor
public class GradeClassificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<Market> KRX_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ);
    private static final Set<Market> US_MARKETS = Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

    private final StockRepository stockRepository;
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final GradeClassifier gradeClassifier;
    private final StockGradePersistService stockGradePersistService;

    /**
     * KRX(KOSPI/KOSDAQ) 종목만 등급 분류한다.
     *
     * <p>활성 종목 빈 결과 시 no-op(REQ-GRADE4-021). 개별 종목 분류 실패 시 해당 종목만 skip(REQ-GRADE4-022). 일봉 결손 종목은 C
     * 분류(REQ-GRADE4-023).
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

        List<Stock> krxStocks =
                allActive.stream().filter(s -> KRX_MARKETS.contains(s.getMarket())).toList();
        if (krxStocks.isEmpty()) {
            return;
        }

        List<Long> stockIds = krxStocks.stream().map(Stock::getId).toList();
        Map<Long, Double> adtvMap = fetchAdtvMap(stockIds);
        ZonedDateTime gradedAt = ZonedDateTime.now(KST);

        for (Stock stock : krxStocks) {
            try {
                long holdingDays = dailyOhlcvRepository.countByStockId(stock.getId());
                double adtv = adtvMap.getOrDefault(stock.getId(), 0.0);
                GradeInput input =
                        new GradeInput(
                                stock.getSymbol(),
                                stock.getNameKo(),
                                stock.getAssetType(),
                                holdingDays,
                                adtv,
                                "KRX");
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
     * <p>활성 종목 빈 결과 시 no-op(REQ-GRADE4-021). 개별 종목 분류 실패 시 해당 종목만 skip(REQ-GRADE4-022). 일봉 결손 종목은 C
     * 분류(REQ-GRADE4-023).
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

        List<Stock> usStocks =
                allActive.stream().filter(s -> US_MARKETS.contains(s.getMarket())).toList();
        if (usStocks.isEmpty()) {
            return;
        }

        List<Long> stockIds = usStocks.stream().map(Stock::getId).toList();
        Map<Long, Double> adtvMap = fetchAdtvMap(stockIds);
        ZonedDateTime gradedAt = ZonedDateTime.now(ET);

        for (Stock stock : usStocks) {
            try {
                long holdingDays = dailyOhlcvRepository.countByStockId(stock.getId());
                double adtv = adtvMap.getOrDefault(stock.getId(), 0.0);
                GradeInput input =
                        new GradeInput(
                                stock.getSymbol(),
                                stock.getNameKo(),
                                stock.getAssetType(),
                                holdingDays,
                                adtv,
                                "US");
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

    private Map<Long, Double> fetchAdtvMap(List<Long> stockIds) {
        if (stockIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = dailyOhlcvRepository.findRecent20DayAdtvByStockIds(stockIds);
        Map<Long, Double> map = new HashMap<>();
        for (Object[] row : rows) {
            Long stockId = ((Number) row[0]).longValue();
            Double adtv = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            map.put(stockId, adtv);
        }
        return map;
    }
}
