package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusSeeder;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 백필 오케스트레이터 (SPEC-COLLECTOR-BACKFILL-001 T6).
 *
 * <p>매 cron 회차의 실행 흐름을 조율한다: lazy 시딩 → 미완료 항목 조회 → 활성 종목 필터 → throttle → 윈도우 실행. 항목 단위 예외를 격리하여 한
 * 항목의 실패가 후속 항목 처리를 막지 않는다(AC-7.1).
 *
 * <p>패키지 위치: {@code stock.backfill} — {@code stock} 피처 패키지가 {@code backfill} 피처 패키지에 의존하는 기존 방향을
 * 유지한다. MdcArchitectureTest noCircularDependenciesBetweenFeaturePackages 준수.
 */
// @MX:ANCHOR: [AUTO] 백필 오케스트레이터 — 매 cron 진입점, lazy 시딩→미완료 선별→throttle→window 실행 담당
// @MX:REASON: [AUTO] SPEC-COLLECTOR-BACKFILL-001 REQ-BACKFILL-006/-008/-020/-033/-034, AC-6/7
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-001
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillOrchestrator {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String TARGET_TYPE_STOCK = "STOCK";

    private final BackfillStatusSeeder seeder;
    private final BackfillStatusRepository backfillStatusRepository;
    private final BackfillWindowExecutor windowExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final StockRepository stockRepository;
    private final BackfillProperties properties;

    /**
     * 백필 1 cron 회차를 실행한다.
     *
     * <p>예외는 스케줄러({@link com.aaa.collector.backfill.BackfillScheduler})가 흡수한다.
     */
    public void run() {
        log.info("[backfill-orchestrator] 백필 cron 시작");

        // Step 1: lazy 시딩 (멱등)
        seeder.seedActiveStocks();

        // Step 2: per-run 세션 1회 고정 (REQ-KISGATE-006a 준수)
        LeaseSession session = keyLeaseRegistry.openSession();

        // Step 3: 활성 종목 맵 구축 (AC-7.4 비활성 종목 스킵)
        Map<String, Stock> activeStockBySymbol = buildActiveStockMap();
        if (activeStockBySymbol.isEmpty()) {
            log.info("[backfill-orchestrator] 활성 종목 없음 — 처리 스킵");
            return;
        }

        // Step 4: 미완료 항목 조회 (PENDING | IN_PROGRESS, STOCK 유형)
        List<BackfillStatus> pending =
                backfillStatusRepository.findByStatusInAndTargetTypeOrderById(
                        List.of(STATUS_PENDING, STATUS_IN_PROGRESS), TARGET_TYPE_STOCK);

        if (pending.isEmpty()) {
            log.info("[backfill-orchestrator] 처리 대상 없음 (AC-6.5)");
            return;
        }

        // Step 5: throttle·처리
        int[] counters = processItems(pending, activeStockBySymbol, session);

        log.info(
                "[backfill-orchestrator] 백필 cron 완료 — processedWindows={}, processedStocks={}",
                counters[0],
                counters[1]);
    }

    /**
     * 미완료 항목을 순회하며 윈도우를 실행하고 처리 카운터를 반환한다.
     *
     * @return int[2] = {윈도우 수, 종목 수}
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 항목 단위 예외 격리 — AC-7.1
    private int[] processItems(
            List<BackfillStatus> pending,
            Map<String, Stock> activeStockBySymbol,
            LeaseSession session) {

        int windowCount = 0;
        int stockCount = 0;
        Set<String> processedStocks = new HashSet<>();

        for (BackfillStatus status : pending) {
            if (windowCount >= properties.getPerRunWindowCap()) {
                log.info(
                        "[backfill-orchestrator] per-run 윈도우 상한 도달 — windowCap={}",
                        properties.getPerRunWindowCap());
                break;
            }

            String symbol = status.getTargetCode();
            Stock stock = activeStockBySymbol.get(symbol);
            if (stock == null) {
                log.debug("[backfill-orchestrator] 비활성 종목 스킵 — symbol={}", symbol);
                continue;
            }

            if (!processedStocks.contains(symbol)) {
                if (stockCount >= properties.getPerRunStockCap()) {
                    log.info(
                            "[backfill-orchestrator] per-run 종목 상한 도달 — stockCap={}",
                            properties.getPerRunStockCap());
                    break;
                }
                processedStocks.add(symbol);
                stockCount++;
            }

            try {
                windowExecutor.executeWindow(status, stock, session);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[backfill-orchestrator] 인터럽트 — 처리 중단. 처리 완료 윈도우={}", windowCount);
                return new int[] {windowCount, stockCount};
            } catch (Exception e) {
                log.warn(
                        "[backfill-orchestrator] 윈도우 오류 (항목 격리) — symbol={}, table={}, error={}",
                        symbol,
                        status.getDataTable(),
                        e.getMessage());
                windowExecutor.executeWindowOnError(
                        status, e.getMessage(), windowExecutor.isRetryable(e));
            }
            windowCount++;
        }

        return new int[] {windowCount, stockCount};
    }

    private Map<String, Stock> buildActiveStockMap() {
        List<Stock> domestic = stockRepository.findAllActiveTradable();
        List<Stock> overseas = stockRepository.findAllActiveOverseasTradable();

        Map<String, Stock> map =
                domestic.stream().collect(Collectors.toMap(Stock::getSymbol, s -> s));
        overseas.forEach(s -> map.putIfAbsent(s.getSymbol(), s));
        return map;
    }
}
