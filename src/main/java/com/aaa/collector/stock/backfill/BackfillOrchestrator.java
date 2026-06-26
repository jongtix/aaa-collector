package com.aaa.collector.stock.backfill;

import com.aaa.collector.backfill.BackfillGroup;
import com.aaa.collector.backfill.BackfillMetrics;
import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusSeeder;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 백필 오케스트레이터 (SPEC-COLLECTOR-BACKFILL-001 T6, SPEC-COLLECTOR-BACKFILL-002 T2).
 *
 * <p>매 cron 회차의 실행 흐름을 조율한다: lazy 시딩 → 미완료 항목 조회 → 활성 종목 필터 → 완성 캡 → status 단위 inner 완성 루프. 각
 * status(종목×데이터테이블)에 대해 {@code COMPLETED}까지 윈도우를 연속 반복하여 단일 회차 내 완성한다(REQ-BACKFILL-050). 항목 단위 예외를
 * 격리하여 한 항목의 실패가 후속 항목 처리를 막지 않는다(AC-7.1).
 *
 * <p>패키지 위치: {@code stock.backfill} — {@code stock} 피처 패키지가 {@code backfill} 피처 패키지에 의존하는 기존 방향을
 * 유지한다. MdcArchitectureTest noCircularDependenciesBetweenFeaturePackages 준수.
 */
// @MX:ANCHOR: [AUTO] 백필 오케스트레이터 — 매 cron 진입점, lazy 시딩→미완료 선별→data_table 그룹핑→테이블별 VT 스트림 병렬 처리 담당
// @MX:REASON: [AUTO] SPEC-COLLECTOR-BACKFILL-001 REQ-BACKFILL-006/-008/-020/-033/-034,
//             SPEC-COLLECTOR-BACKFILL-002 REQ-BACKFILL-050/-053a/-053b/-055/-057/-059.
//             SPEC-COLLECTOR-BACKFILL-004 REQ-BACKFILL-061~070 — 테이블별 스트림 병렬(REQ-063),
//             테이블별 캡(REQ-064, perRunCompletionCap supersede), 공유 LeaseSession(REQ-066).
//             inner 루프: 각 status를 COMPLETED까지 반복(GROUP_A=anchor 무전진 break, GROUP_B=미적용).
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-001, SPEC-COLLECTOR-BACKFILL-002, SPEC-COLLECTOR-BACKFILL-004
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillOrchestrator {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String TARGET_TYPE_STOCK = "STOCK";

    private final BackfillStatusSeeder seeder;
    private final BackfillStatusRepository backfillStatusRepository;
    private final BackfillWindowExecutor windowExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final StockRepository stockRepository;
    private final BackfillProperties properties;
    private final BackfillMetrics backfillMetrics;

    /**
     * 백필 1 cron 회차를 실행한다.
     *
     * <p>예외는 스케줄러({@link BackfillScheduler})가 흡수한다.
     */
    public void run() {
        log.info("[backfill-orchestrator] 백필 cron 시작");

        // Step 1: lazy 시딩 (멱등)
        seeder.seedActiveStocks();

        // Step 2: per-run 세션 1회 고정 (REQ-KISGATE-006a 준수 — inner 루프 재오픈 금지)
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

        // Step 5: data_table 기준 인메모리 그룹핑 (REQ-BACKFILL-062)
        Map<String, List<BackfillStatus>> byTable =
                pending.stream().collect(Collectors.groupingBy(BackfillStatus::getDataTable));

        // Step 6: 테이블별 Virtual Thread 스트림 동시 처리 (REQ-BACKFILL-063)
        int[] counters = processTableStreams(byTable, activeStockBySymbol, session);

        // Step 7: 진행률 gauge 갱신 (REQ-BACKFILL-040)
        long completedCount =
                backfillStatusRepository.countByStatusAndTargetType(
                        STATUS_COMPLETED, TARGET_TYPE_STOCK);
        long totalCount = backfillStatusRepository.countByTargetType(TARGET_TYPE_STOCK);
        backfillMetrics.recordProgress(completedCount, totalCount);

        log.info(
                "[backfill-orchestrator] 백필 cron 완료 — completedSlots={}, processedWindows={}",
                counters[0],
                counters[1]);
    }

    /**
     * 모든 data_table 스트림을 Virtual Thread로 동시 실행하고 합산 카운터를 반환한다.
     *
     * <p>테이블별 스트림은 독립적으로 실행되며, 한 스트림의 인터럽트가 다른 스트림에 영향을 주지 않는다 (REQ-BACKFILL-070).
     * try-with-resources가 모든 VT 태스크의 완료를 보장한다.
     *
     * @return int[2] = {전체 처리 status 슬롯 합산, 전체 처리 윈도우 합산}
     */
    // @MX:WARN: [AUTO] processTableStreams — Executors.newVirtualThreadPerTaskExecutor() 병렬 처리
    // @MX:REASON: [AUTO] 테이블별 VT 스트림 동시 실행(REQ-BACKFILL-063). try-with-resources 자동 셧다운 보장.
    //             InterruptedException은 해당 스트림만 종료(REQ-BACKFILL-070) — 다른 스트림은 join까지 계속.
    private int[] processTableStreams(
            Map<String, List<BackfillStatus>> byTable,
            Map<String, Stock> activeStockBySymbol,
            LeaseSession session) {
        // try-with-resources: ExecutorService auto-close waits for all submitted tasks
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<int[]>> futures =
                    byTable.values().stream()
                            .map(
                                    tableStatuses ->
                                            executor.submit(
                                                    () ->
                                                            processTableStream(
                                                                    tableStatuses,
                                                                    activeStockBySymbol,
                                                                    session)))
                            .toList();

            int totalCompleted = 0;
            int totalWindows = 0;
            for (Future<int[]> future : futures) {
                try {
                    int[] result = future.get();
                    totalCompleted += result[0];
                    totalWindows += result[1];
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[backfill-orchestrator] 스트림 join 인터럽트");
                } catch (ExecutionException e) {
                    log.warn("[backfill-orchestrator] 스트림 실행 예외 — {}", e.getMessage());
                }
            }
            return new int[] {totalCompleted, totalWindows};
        }
    }

    /**
     * 단일 data_table 스트림 처리.
     *
     * <p>테이블별 {@code perTableCompletionCap} 캡을 적용하여 status 목록을 순회한다. InterruptedException은 해당
     * 스트림(테이블)만 종료시키며 다른 스트림에 영향을 주지 않는다(REQ-BACKFILL-070).
     *
     * @return int[2] = {처리한 status 슬롯 수, 처리한 총 윈도우 수}
     */
    private int[] processTableStream(
            List<BackfillStatus> tableStatuses,
            Map<String, Stock> activeStockBySymbol,
            LeaseSession session) {
        int completedSlots = 0;
        int processedWindows = 0;

        for (BackfillStatus status : tableStatuses) {
            // 테이블별 캡 (REQ-BACKFILL-064)
            if (completedSlots >= properties.getPerTableCompletionCap()) {
                break;
            }

            String symbol = status.getTargetCode();
            Stock stock = activeStockBySymbol.get(symbol);
            if (stock == null) {
                log.debug("[backfill-orchestrator] 비활성 종목 스킵 — symbol={}", symbol);
                continue;
            }

            // inner 루프 개시 = 슬롯 1 소비 (완성 여부·안전장치 중단 무관)
            completedSlots++;

            // inner 완성 루프: status가 COMPLETED될 때까지 반복 (REQ-BACKFILL-050)
            InnerLoopResult result = runInnerLoop(status, stock, session);
            processedWindows += result.windowCount;

            if (result.interrupted) {
                // 이 스트림(테이블)만 종료, 나머지 스트림은 join (REQ-BACKFILL-070)
                log.warn("[backfill-orchestrator] 스트림 인터럽트 종료 — table={}", status.getDataTable());
                break;
            }
        }
        return new int[] {completedSlots, processedWindows};
    }

    /** inner 완성 루프 실행 결과. */
    private static final class InnerLoopResult {
        final int windowCount;
        final boolean interrupted;

        InnerLoopResult(int windowCount, boolean interrupted) {
            this.windowCount = windowCount;
            this.interrupted = interrupted;
        }
    }

    /**
     * 단일 status에 대해 inner 완성 루프를 실행한다 (SPEC-COLLECTOR-BACKFILL-002 §1.1, 방안 A).
     *
     * <p>종료 조건:
     *
     * <ul>
     *   <li>재조회 status = COMPLETED (REQ-BACKFILL-057)
     *   <li>status당 윈도우 수 ≥ {@code maxWindowsPerTarget} (공통 하드 캡, REQ-BACKFILL-053a)
     *   <li>GROUP_A 전용: 재조회 {@code lastCollectedDate} = 직전과 동일(anchor 무전진), 첫 윈도우 면제
     *       (REQ-BACKFILL-053b)
     *   <li>윈도우 예외 (REQ-BACKFILL-055) — 해당 status inner 루프 종료, 다음 status로 진행
     * </ul>
     *
     * @param status 처리할 초기 status
     * @param stock 대상 종목
     * @param session KIS 키 임대 세션
     * @return 루프 실행 결과 (처리 윈도우 수·인터럽트 여부)
     */
    private InnerLoopResult runInnerLoop(BackfillStatus status, Stock stock, LeaseSession session) {
        BackfillGroup group = BackfillGroup.ofDataTable(status.getDataTable());
        BackfillStatus current = status;
        int windowsForThis = 0;

        while (true) {
            // 공통 하드 캡 (REQ-BACKFILL-053a, GROUP_A·B 모두)
            if (windowsForThis >= properties.getMaxWindowsPerTarget()) {
                log.warn(
                        "[backfill-orchestrator] 하드 캡 도달 — symbol={}, table={}, maxWindows={}",
                        current.getTargetCode(),
                        current.getDataTable(),
                        properties.getMaxWindowsPerTarget());
                break;
            }

            LocalDate prevDate = current.getLastCollectedDate();

            // 윈도우 실행 (독립 트랜잭션) — InterruptedException 시 회차 중단, 예외 시 status 루프 종료
            InnerLoopResult earlyExit = executeOneWindow(current, stock, session, windowsForThis);
            if (earlyExit != null) {
                return earlyExit;
            }

            // 방안 A: 윈도우 후 status 재조회 (REQ-BACKFILL-057)
            BackfillStatus refreshed =
                    backfillStatusRepository.findById(current.getId()).orElse(current);

            windowsForThis++;

            if (isCompleted(refreshed, current, windowsForThis)) {
                break;
            }
            if (isGroupAAnchorStalled(group, prevDate, refreshed)) {
                log.warn(
                        "[backfill-orchestrator] GROUP_A anchor 무전진 감지 — 조기 중단. symbol={}, table={}, anchor={}",
                        current.getTargetCode(),
                        current.getDataTable(),
                        prevDate);
                break;
            }

            current = refreshed;
        }

        return new InnerLoopResult(windowsForThis, false);
    }

    /**
     * 윈도우 1회 실행. 예외 발생 시 조기 반환 값을 돌려주고, 정상 완료 시 {@code null}을 반환한다.
     *
     * <p>{@code InterruptedException}: 인터럽트 플래그 복원 후 {@link InnerLoopResult#interrupted}=true 반환
     * (REQ-TXB-032). {@code Exception}: 오류 기록 후 {@code windowsForThis}개 처리한 결과(not interrupted)를 반환
     * (REQ-TXB-033).
     *
     * <p>fetch와 persist 양쪽을 단일 {@code try}로 감싸 예외 누락을 방지한다 (REQ-TXB-034).
     */
    // @MX:WARN: [AUTO] self-invocation 방지: windowExecutor.persistWindow는 반드시 교차 빈 호출 유지.
    // @MX:REASON: [AUTO] 오케스트레이터 외부에서 fetchWindow→persistWindow 순서를 변경하거나 실행기 내부에서
    //             호출하면 @Transactional 프록시 우회로 원자성 소실(REQ-TXB-030/REQ-BACKFILL-011).
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 윈도우 단위 예외 격리 — REQ-BACKFILL-055
    private InnerLoopResult executeOneWindow(
            BackfillStatus current, Stock stock, LeaseSession session, int windowsForThis) {
        try {
            // 교차 빈 호출: fetchWindow(비tx) → persistWindow(@Transactional) (REQ-TXB-012/-034/-040)
            Object fetchDto = windowExecutor.fetchWindow(current, stock, session);
            windowExecutor.persistWindow(current, stock, fetchDto);
            return null; // 정상 — 루프 계속
        } catch (InterruptedException e) {
            // 회차 즉시 중단 (REQ-BACKFILL-056, REQ-TXB-032)
            Thread.currentThread().interrupt();
            return new InnerLoopResult(windowsForThis, true);
        } catch (Exception e) {
            // status inner 루프 종료, 다음 status로 진행 (REQ-BACKFILL-055, REQ-TXB-033)
            log.warn(
                    "[backfill-orchestrator] 윈도우 오류 (항목 격리) — symbol={}, table={}, error={}",
                    current.getTargetCode(),
                    current.getDataTable(),
                    e.getMessage());
            windowExecutor.executeWindowOnError(
                    current, e.getMessage(), windowExecutor.isRetryable(e));
            return new InnerLoopResult(windowsForThis, false);
        }
    }

    /** 재조회 status가 COMPLETED인지 확인하고 debug 로그를 남긴다. */
    private boolean isCompleted(
            BackfillStatus refreshed, BackfillStatus current, int windowsForThis) {
        if (!STATUS_COMPLETED.equals(refreshed.getStatus())) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "[backfill-orchestrator] COMPLETED — symbol={}, table={}, windows={}",
                    current.getTargetCode(),
                    current.getDataTable(),
                    windowsForThis);
        }
        return true;
    }

    /**
     * GROUP_A anchor 무전진 여부를 반환한다 (REQ-BACKFILL-053b).
     *
     * <p>GROUP_B는 미적용 — decideGroupB가 무전진을 stale_count로 누적해 COMPLETED 처리(D1). 첫 윈도우(prevDate=null)
     * 면제(D5).
     */
    private boolean isGroupAAnchorStalled(
            BackfillGroup group, LocalDate prevDate, BackfillStatus refreshed) {
        return group == BackfillGroup.GROUP_A
                && prevDate != null
                && prevDate.equals(refreshed.getLastCollectedDate());
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
