package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.CoveredRangeService;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService;
import com.aaa.collector.stock.daily.OverseasDailyOhlcvCollectionService;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.supply.CreditBalanceCollectionService;
import com.aaa.collector.stock.supply.InvestorTrendCollectionService;
import com.aaa.collector.stock.supply.ShortSaleCollectionService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** 미국 동부시간(ET) — 해외 종목 상한 산출용(aaa-infra#112). */
    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * 해외(NYSE/NASDAQ/AMEX) 시장 판정 — {@link StockRangeCoveredGapFiller#OVERSEAS_MARKETS}와 동일 패턴을 독립
     * 상수로 유지한다(진입점에서 캡 산출에 필요, 두 클래스가 서로 참조하지 않도록 REQ-CVR-012 독립 경로 원칙을 지킨다).
     */
    private static final Set<Market> OVERSEAS_MARKETS =
            Set.of(Market.NYSE, Market.NASDAQ, Market.AMEX);

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
    private final Clock clock;

    /**
     * 4개 data_table 전체({@code COMPLETED} 포함)를 순회하며 정방향 갭 walk를 수행한다.
     *
     * @param activeStockBySymbol 활성 종목 맵 — 비활성 종목은 스킵한다(AC-7.4와 동일 정신)
     * @param session per-run 고정 KIS 헬스 스냅샷 세션 (재오픈 금지, REQ-KISGATE-006a)
     */
    public void runFor(Map<String, Stock> activeStockBySymbol, LeaseSession session) {
        for (String dataTable : COVERED_DATA_TABLES) {
            List<BackfillStatus> statuses =
                    backfillStatusRepository.findByTargetTypeAndDataTableOrderById(
                            TARGET_TYPE_STOCK, dataTable);
            for (BackfillStatus status : statuses) {
                Stock stock = activeStockBySymbol.get(status.getTargetCode());
                if (stock == null) {
                    continue; // 비활성 종목 스킵
                }
                runOne(status, stock, session);
            }
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 대상 단위 예외 격리 — AC-7.1과 동일 정신
    private void runOne(BackfillStatus status, Stock stock, LeaseSession session) {
        try {
            LocalDate cap = resolveCap(stock);
            StockRangeCoveredGapFiller filler =
                    new StockRangeCoveredGapFiller(
                            status,
                            stock,
                            cap,
                            session,
                            domesticOhlcvService,
                            overseasOhlcvService,
                            investorTrendService,
                            creditBalanceService,
                            shortSaleService);
            coveredRangeService.walkGapForward(status, filler, cap);
        } catch (Exception e) {
            log.error(
                    "[stock-covered-gap-walk] 예외 — symbol={}, table={}, 다음 회차 재개",
                    status.getTargetCode(),
                    status.getDataTable(),
                    e);
        }
    }

    // @MX:NOTE: [AUTO] 해외 종목은 ET 전일을 상한으로 캡 — KST 벽시계를 그대로 쓰면 매일 02:00 KST 크론 발화 시점에
    // 미국 장이 아직 전일(ET) 거래 중이라 KIS 미확정(장중) 부분바가 반환되고 INSERT IGNORE로 영구 저장되어 확정바 업데이트를
    // 영구 차단한다(aaa-infra#112). 진입점(runOne)에서 산출해 filler 생성자·walkGapForward 양쪽에 동일 값으로 주입한다 —
    // StockRangeCoveredGapFiller 내부 캡(구 후보 A)은 바깥 루프 today가 캡되지 않아 비종료 재요청 루프 위험이 있어 기각됨.
    // @MX:REASON: aaa-infra#112 근본원인 대칭 조치, DP-1 확정안(진입점 캡). aaa-infra#91과 동일 정신(ET 대칭 처리).
    // @MX:SPEC: SPEC-COLLECTOR-BACKFILL-012
    LocalDate resolveCap(Stock stock) {
        if (OVERSEAS_MARKETS.contains(stock.getMarket())) {
            return LocalDate.now(clock.withZone(ET)).minusDays(1);
        }
        return LocalDate.now(clock.withZone(KST));
    }
}
