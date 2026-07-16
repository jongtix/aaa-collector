package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * STOCK 범위형 4종(daily_ohlcv·investor_trend·credit_balance·short_sale_domestic) 정방향 갭 walk 실행기
 * (SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011, -050, -060).
 *
 * <p>{@link BackfillOrchestrator}에서 {@link StockRangeCoveredGapFiller} 구성에 필요한 5개 수집 서비스 의존을 분리해낸
 * 협력자 — PMD {@code CouplingBetweenObjects} 임계(25) 초과를 억제 주석이 아닌 구조 추출로 해소한다(TASK-007과 동일 결정: 프로젝트
 * HARD 룰상 PMD 억제 목록 추가는 사용자 승인 없이 채택하지 않는다).
 *
 * <p>대상당(종목×data_table) 예외를 격리한다(AC-7.1과 동일 정신) — 한 대상의 실패가 나머지 대상 처리를 막지 않는다.
 */
// @MX:ANCHOR: [AUTO] STOCK 범위형 정방향 갭 walk 진입점 — BackfillOrchestrator.run()이 매 회차 호출
// @MX:REASON: SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011/-041/-050/-060. backward walk의
// PENDING/IN_PROGRESS 필터와 무관하게 항상 시도해야 하므로 BackfillOrchestrator의 이른 return들 밖에서 호출된다.
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-011
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCoveredGapWalkRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 커버-추적 대상 STOCK 범위형 data_table 4종. */
    private static final List<String> COVERED_DATA_TABLES =
            List.of("daily_ohlcv", "investor_trend", "credit_balance", "short_sale_domestic");

    private static final String TARGET_TYPE_STOCK = "STOCK";

    private final BackfillStatusRepository backfillStatusRepository;
    private final CoveredRangeService coveredRangeService;
    private final DomesticDailyOhlcvCollectionService domesticOhlcvService;
    private final OverseasDailyOhlcvCollectionService overseasOhlcvService;
    private final InvestorTrendCollectionService investorTrendService;
    private final CreditBalanceCollectionService creditBalanceService;
    private final ShortSaleCollectionService shortSaleService;

    /**
     * 4개 data_table 전체({@code COMPLETED} 포함)를 순회하며 정방향 갭 walk를 수행한다.
     *
     * @param activeStockBySymbol 활성 종목 맵 — 비활성 종목은 스킵한다(AC-7.4와 동일 정신)
     * @param session per-run 고정 KIS 헬스 스냅샷 세션 (재오픈 금지, REQ-KISGATE-006a)
     */
    public void runFor(Map<String, Stock> activeStockBySymbol, LeaseSession session) {
        LocalDate today = LocalDate.now(KST);
        for (String dataTable : COVERED_DATA_TABLES) {
            List<BackfillStatus> statuses =
                    backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            TARGET_TYPE_STOCK, dataTable);
            for (BackfillStatus status : statuses) {
                Stock stock = activeStockBySymbol.get(status.getTargetCode());
                if (stock == null) {
                    continue; // 비활성 종목 스킵
                }
                runOne(status, stock, today, session);
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 대상 단위 예외 격리 — AC-7.1과 동일 정신
    private void runOne(BackfillStatus status, Stock stock, LocalDate today, LeaseSession session) {
        try {
            StockRangeCoveredGapFiller filler =
                    new StockRangeCoveredGapFiller(
                            status,
                            stock,
                            today,
                            session,
                            domesticOhlcvService,
                            overseasOhlcvService,
                            investorTrendService,
                            creditBalanceService,
                            shortSaleService);
            coveredRangeService.walkGapForward(status, filler, today);
        } catch (Exception e) {
            log.error(
                    "[stock-covered-gap-walk] 예외 — symbol={}, table={}, 다음 회차 재개",
                    status.getTargetCode(),
                    status.getDataTable(),
                    e);
        }
    }
}
