package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 미국 공매도 Daily 과거 백필 오케스트레이터 (SPEC-COLLECTOR-BACKFILL-008 T4).
 *
 * <p>기존 KIS 종목×테이블 백필({@code BackfillOrchestrator} 등)과 구조적으로 무관한 독립 컴포넌트다. 진행 상태는 {@code
 * backfill_status} 전역 앵커 1행({@code target_type="OVERSEAS_SHORTSALE"}, {@code
 * target_code="__GLOBAL__"}, {@code data_table="short_sale_overseas"})만
 * 사용하며(REQ-BACKFILL-111/-111a), 종목별 상태 행을 생성하지 않는다.
 *
 * <p>크론 1회 로직(plan.md §4): 앵커 멱등 시딩 → 로드 → COMPLETED면 신규 종목 편입 감지 시 리셋(REQ-BACKFILL-116) 아니면 종료 →
 * 활성 미국 tradable 종목 1회 로드(REQ-BACKFILL-108) → 앵커부터 과거로 {@code perCronDateCap}일 순회하며 날짜당 CDN 파일 1회
 * 취득(REQ-BACKFILL-112/-112a) → {@link FinraCdnDailyLoader}에 위임해 시설 파일 종목별
 * 합산·매칭·UPSERT(REQ-BACKFILL-104, -117/-118) → 앵커 전진 → floor 도달 시에만 COMPLETED(유일 종료,
 * REQ-BACKFILL-114) → 전역 앵커의 정방향 갭 walk를 {@link FinraCdnCoveredGapWalkRunner}에
 * 위임(REQ-CVR-011/-070/-071).
 *
 * <p>책임 분리(코드리뷰 — PMD CouplingBetweenObjects 완화, 억제 주석 대신 구조 추출): 이 클래스는 전역 앵커 조회+생명주기(시딩·리셋
 * 판정·backward walk 커서 전진)만 담당한다. 파싱·심볼 매칭·UPSERT는 {@link FinraCdnDailyLoader}(구현 {@link
 * FinraCdnDailyLoaderImpl})로, 정방향 갭 walk 실행은 {@link FinraCdnCoveredGapWalkRunner}로 위임한다.
 */
// @MX:ANCHOR: [AUTO] 백필 진입점 — 전역 앵커 단조 전진·floor 유일 종료
// @MX:REASON: [AUTO] 스케줄러(FinraCdnShortSaleBackfillScheduler)의 유일 호출 지점이며 크론 1회 전체 사이클을 담당한다.
// @MX:NOTE: [AUTO] interest 파라미터 null 전달 = short_interest/short_interest_date COALESCE
// 보존(REQ-BACKFILL-118);
// 시설 200만 합산 = OQ5 안전(NMS 비겹침 실측); count 기반 리셋은 동수 편입+이탈 net-zero 사각 있음 — 운영자 강제 리셋으로 완화(R3)
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
@Slf4j
@Component
@RequiredArgsConstructor
public class FinraCdnShortSaleBackfillOrchestrator {

    static final String TARGET_TYPE = "OVERSEAS_SHORTSALE";
    static final String TARGET_CODE = "__GLOBAL__";
    static final String DATA_TABLE = "short_sale_overseas";

    private static final List<BackfillStatusType> ALL_STATUSES =
            List.of(
                    BackfillStatusType.PENDING,
                    BackfillStatusType.IN_PROGRESS,
                    BackfillStatusType.COMPLETED);

    /** BatchMetrics 배치 라벨 — CDN 백필 경로(종전 Micrometer 계측 전무, REQ-SSD-009). */
    private static final String BATCH_BACKFILL = "overseas-shortsale-backfill";

    private final BackfillStatusRepository backfillStatusRepository;
    private final StockRepository stockRepository;
    private final FinraCdnDailyFileClient client;
    private final FinraCdnDailyLoader dailyLoader;
    private final FinraCdnShortSaleBackfillProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final BatchMetrics batchMetrics;
    private final FinraCdnCoveredGapWalkRunner coveredGapWalkRunner;

    /**
     * 크론 1회 백필 사이클 진입점.
     *
     * <p>backward walk(하단, {@code last_collected_date})에 이어 전역 앵커의 정방향 갭 walk(상단, {@code
     * covered_until_date})를 항상 시도한다(SPEC-COLLECTOR-BACKFILL-011 REQ-CVR-011, -070) — backward walk가
     * COMPLETED 유지로 조기 반환하더라도 상단 라이브 갭은 이 회차가 계속 책임진다(§1.1 근본원인, TASK-006 USDKRW와 동일 패턴).
     */
    public void run() {
        backfillStatusRepository.insertIgnoreSeed(TARGET_TYPE, TARGET_CODE, DATA_TABLE);

        List<BackfillStatus> anchors =
                backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                        ALL_STATUSES, TARGET_TYPE, DATA_TABLE);
        if (anchors.isEmpty()) {
            log.error("[finra-cdn-backfill] 앵커 행 시딩 실패 — insertIgnoreSeed 이후 조회 결과 없음");
            return;
        }
        BackfillStatus anchor = anchors.getFirst();
        Long anchorId = anchor.getId();

        List<Stock> tradableStocks = stockRepository.findAllActiveOverseasTradable();
        int currentActiveUsCount = tradableStocks.size();
        Map<String, Stock> symbolMap = buildSymbolMap(tradableStocks);

        LocalDate resumeFrom = resolveResumeFrom(anchor, anchorId, currentActiveUsCount);
        if (resumeFrom != null) {
            runCycle(anchorId, resumeFrom, symbolMap, currentActiveUsCount);
        }

        runCoveredGapWalk(anchorId, symbolMap);
    }

    /**
     * 전역 앵커 1행을 재조회해 {@link FinraCdnCoveredGapWalkRunner}에 정방향 갭 walk 실행을 위임한다 (REQ-CVR-071 — 종목별
     * 신규 행 생성 절대 금지, BACKFILL-008 불변식).
     *
     * <p>DP-4: 이 호출은 backward walk의 {@link #advanceAnchor}(하단 커밋, {@code transactionTemplate})와 완전히
     * 독립된 최상위 호출이다 — {@code advanceAnchor}의 트랜잭션 콜백 안에 중첩하지 않는다. {@link
     * FinraCdnCoveredGapWalkRunner#runFor}는 내부적으로 자신의 {@code CoveredRangeService}가 스텝마다 별도 {@code
     * TransactionTemplate}으로 트랜잭션을 커밋하므로(TASK-003 원자성 단위), 정방향 증분으로 상단 커밋 빈도가 하단보다 훨씬 잦아져도 두 갱신이 서로
     * 얽히지 않는다.
     */
    private void runCoveredGapWalk(Long anchorId, Map<String, Stock> symbolMap) {
        BackfillStatus freshAnchor = backfillStatusRepository.findById(anchorId).orElse(null);
        if (freshAnchor == null) {
            log.warn("[finra-cdn-backfill] 갭 walk skip — 앵커 재조회 실패(id={})", anchorId);
            return;
        }
        coveredGapWalkRunner.runFor(freshAnchor, LocalDate.now(), dailyLoader, symbolMap);
    }

    /**
     * COMPLETED 리셋 판정을 수행하고, 이번 사이클의 순회 시작일을 결정한다.
     *
     * @return 순회 시작일, 또는 리셋 불필요(완료 유지)로 이번 사이클을 종료해야 하면 {@code null}
     */
    private LocalDate resolveResumeFrom(
            BackfillStatus anchor, Long anchorId, int currentActiveUsCount) {
        if (anchor.getStatus() != BackfillStatusType.COMPLETED) {
            LocalDate lastCollectedDate = anchor.getLastCollectedDate();
            return lastCollectedDate == null ? LocalDate.now() : lastCollectedDate.minusDays(1);
        }

        Integer coveredCount = anchor.getLastRowCount();
        boolean shouldReset = coveredCount == null || currentActiveUsCount > coveredCount;
        if (!shouldReset) {
            log.info(
                    "[finra-cdn-backfill] COMPLETED 유지 — coveredCount={}, currentActiveUsCount={}",
                    coveredCount,
                    currentActiveUsCount);
            return null;
        }

        LocalDate today = LocalDate.now();
        advanceAnchor(anchorId, BackfillStatusType.IN_PROGRESS, today, null);
        log.info("[finra-cdn-backfill] 신규 종목 편입 감지 — 리셋(IN_PROGRESS, anchor={})", today);
        return today.minusDays(1);
    }

    /**
     * 앵커부터 과거로 {@code perCronDateCap}일 순회하며 날짜별 파일 취득·{@link FinraCdnDailyLoader} 위임·앵커 전진을 수행한다.
     */
    private void runCycle(
            Long anchorId,
            LocalDate resumeFrom,
            Map<String, Stock> symbolMap,
            int currentActiveUsCount) {
        LocalDate floor = properties.getFloorDate();
        int perCronDateCap = properties.getPerCronDateCap();
        LocalDate date = resumeFrom;
        CycleAccumulator acc = new CycleAccumulator();

        while (!date.isBefore(floor) && acc.processedDays < perCronDateCap) {
            FinraCdnFetchResult fetchResult = client.fetch(date);
            if (fetchResult instanceof FinraCdnFetchResult.Found found) {
                FinraCdnDailyLoadOutcome outcome =
                        dailyLoader.loadDate(date, found.fileBodies(), symbolMap);
                acc.parseSkips += outcome.skipped();
                acc.matchFailures += outcome.unmatched();
            } else if (fetchResult instanceof FinraCdnFetchResult.Absent absent) {
                if (absent.reason() == FinraCdnFetchResult.AbsenceReason.TRANSIENT_ERROR) {
                    log.warn(
                            "[finra-cdn-backfill] 일시적 오류로 이번 사이클 중단 — date={}, 앵커는 전진하지 않고 다음 크론에서"
                                    + " 동일 지점부터 재시도",
                            date);
                    break;
                }
                acc.absentDays++;
                if (log.isDebugEnabled()) {
                    log.debug(
                            "[finra-cdn-backfill] 파일 부재 — date={}, reason={}",
                            date,
                            absent.reason());
                }
            }
            acc.oldestProcessed = date;
            date = date.minusDays(1);
            acc.processedDays++;
        }

        if (acc.oldestProcessed == null) {
            log.warn("[finra-cdn-backfill] 이번 사이클 진행 없음 — perCronDateCap={}", perCronDateCap);
            return;
        }

        if (date.isBefore(floor)) {
            advanceAnchor(anchorId, BackfillStatusType.COMPLETED, floor, currentActiveUsCount);
        } else {
            advanceAnchor(
                    anchorId,
                    BackfillStatusType.IN_PROGRESS,
                    acc.oldestProcessed,
                    currentActiveUsCount);
        }

        // REQ-SSD-009: 파싱 거부(음수·scale 초과·형식 오류) 건수를 last_load와 독립적인 카운터로 계측 —
        // 종전 Micrometer 계측이 전무하던 CDN 백필 경로를 관측 가능하게 노출한다(소수부는 더 이상 거부 사유가 아님).
        batchMetrics.recordParseRejections(BATCH_BACKFILL, acc.parseSkips);

        log.info(
                "[finra-cdn-backfill] 사이클 완료 — oldest={}, processedDays={}, absentDays={},"
                        + " parseSkips={}, matchFailures={}",
                acc.oldestProcessed,
                acc.processedDays,
                acc.absentDays,
                acc.parseSkips,
                acc.matchFailures);
    }

    /** {@link #runCycle} 순회 중 누적 상태 — PMD VariableDeclarationUsageDistance 회피용 값 객체. */
    private static final class CycleAccumulator {
        private LocalDate oldestProcessed;
        private int processedDays;
        private int absentDays;
        private int parseSkips;
        private int matchFailures;
    }

    private static Map<String, Stock> buildSymbolMap(List<Stock> stocks) {
        Map<String, Stock> map = new ConcurrentHashMap<>();
        for (Stock stock : stocks) {
            map.put(stock.getSymbol(), stock);
        }
        return map;
    }

    private void advanceAnchor(
            Long anchorId,
            BackfillStatusType status,
            LocalDate lastCollectedDate,
            Integer lastRowCount) {
        transactionTemplate.executeWithoutResult(
                tx -> {
                    BackfillStatus managed =
                            backfillStatusRepository.findById(anchorId).orElseThrow();
                    managed.advance(status, lastCollectedDate, 0, lastRowCount);
                });
    }
}
