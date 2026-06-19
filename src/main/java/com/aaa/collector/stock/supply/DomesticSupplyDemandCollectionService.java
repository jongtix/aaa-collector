package com.aaa.collector.stock.supply;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내 수급 3종 통합 진입점 (REQ-BATCH2-005).
 *
 * <p>활성 관심종목을 1회 조회하여 3종이 공유하고(중복 조회 회피), 고정 순서(투자자 매매동향 → 공매도 → 신용잔고)로 종(種)별 순차 수집한다. 각 종 수집 예외를 종
 * 단위로 격리(catch·로그)하여 한 종 실패가 다음 종을 막지 않는다(REQ-BATCH2-062 가시성).
 *
 * <p>스케줄러 인라인 체인(T8)이 일봉 수집 + {@code stream:daily:complete} 발행 후 본 진입점을 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 수급 3종 통합 진입점 — 활성종목 공유·고정순서·종 단위 예외 격리
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-005,-010,-062 — 스케줄러 인라인 체인(T8)이 호출
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002
public class DomesticSupplyDemandCollectionService {

    /** 배치 계측 라벨 접두사 — {@code kind}와 결합해 3 per-kind 라벨을 만든다 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL_PREFIX = "domestic-supply-";

    private final StockRepository stockRepository;
    private final InvestorTrendCollectionService investorTrendService;
    private final ShortSaleCollectionService shortSaleService;
    private final CreditBalanceCollectionService creditBalanceService;
    private final BatchMetrics batchMetrics;

    /**
     * 수급 3종을 고정 순서로 순차 수집한다.
     *
     * @param today 수집 기준일
     */
    public void collectAll(LocalDate today) {
        // REQ-BATCH3-024: per-stock 대상은 STOCK+ETF만 — INDEX 헛호출 제거 (StockRepository 계층에서 캡슐화)
        List<Stock> activeStocks = stockRepository.findAllActiveTradable();
        log.info(
                "[supply-demand] 수급 3종 수집 시작 — activeStocks={}, today={}",
                activeStocks.size(),
                today);

        runKind("investor", today, activeStocks, investorTrendService::collect);
        runKind("short-sale", today, activeStocks, shortSaleService::collect);
        runKind("credit-balance", today, activeStocks, creditBalanceService::collect);

        log.info("[supply-demand] 수급 3종 수집 종료 — today={}", today);
    }

    /**
     * 한 종의 수집을 실행하고 예외를 종 단위로 격리한다(REQ-BATCH2-062).
     *
     * <p>한 종 수집 예외가 다음 종을 막지 않도록 catch·로그한다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 종 단위 예외 격리 — 한 종 실패가 다음 종을 막지 않게
    private void runKind(
            String kind,
            LocalDate today,
            List<Stock> activeStocks,
            BiFunction<LocalDate, List<Stock>, SupplyDemandResult> collector) {
        try {
            SupplyDemandResult result = collector.apply(today, activeStocks);
            // REQ-OBSV-020/021: per-kind 배치 집계 계측 — fail은 attempted-succeeded-skipped로 유도.
            long fail =
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL_PREFIX + kind,
                    result.attempted(),
                    result.succeeded(),
                    fail,
                    result.skipped());
            log.info(
                    "[supply-demand] {} 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    kind,
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[supply-demand] {} 수집 예외 — 다음 종 계속 진행", kind, e);
        }
    }
}
