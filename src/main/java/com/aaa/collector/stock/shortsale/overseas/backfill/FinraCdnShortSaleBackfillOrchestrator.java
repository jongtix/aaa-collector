package com.aaa.collector.stock.shortsale.overseas.backfill;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.backfill.BackfillStatusType;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.shortsale.overseas.FinraSymbolNormalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 취득(REQ-BACKFILL-112/-112a) → 시설 파일 종목별 합산(REQ-BACKFILL-104) → 매칭 종목 UPSERT(interest 파라미터 null 전달로
 * 기존 Short Interest 보존, REQ-BACKFILL-117/-118) → 앵커 전진 → floor 도달 시에만 COMPLETED(유일 종료,
 * REQ-BACKFILL-114).
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

    private final BackfillStatusRepository backfillStatusRepository;
    private final StockRepository stockRepository;
    private final FinraCdnDailyFileClient client;
    private final FinraCdnFileParser parser;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final FinraCdnShortSaleBackfillProperties properties;
    private final TransactionTemplate transactionTemplate;

    /** 크론 1회 백필 사이클 진입점. */
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

        LocalDate resumeFrom = resolveResumeFrom(anchor, anchorId, currentActiveUsCount);
        if (resumeFrom == null) {
            return;
        }

        runCycle(anchorId, resumeFrom, buildSymbolMap(tradableStocks), currentActiveUsCount);
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

    /** 앵커부터 과거로 {@code perCronDateCap}일 순회하며 날짜별 파일 취득·매칭·적재·앵커 전진을 수행한다. */
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
                DailyLoadOutcome outcome = loadDate(date, found.fileBodies(), symbolMap);
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

    /** 하루치 파일 본문(다중 시설 가능)을 종목별로 합산·매칭·UPSERT한다(REQ-BACKFILL-104/-117/-118/-119). */
    private DailyLoadOutcome loadDate(
            LocalDate date, List<String> fileBodies, Map<String, Stock> symbolMap) {
        Map<String, Long> shortSums = new ConcurrentHashMap<>();
        Map<String, Long> totalSums = new ConcurrentHashMap<>();
        int skipped = 0;
        for (String body : fileBodies) {
            ParsedFileResult parsed = parser.parse(body);
            skipped += parsed.skippedCount();
            for (ParsedRow row : parsed.rows()) {
                String normalized = FinraSymbolNormalizer.normalize(row.symbol());
                shortSums.merge(normalized, row.shortVolume(), Long::sum);
                totalSums.merge(normalized, row.totalVolume(), Long::sum);
            }
        }

        int unmatched = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Long> entry : shortSums.entrySet()) {
            String symbol = entry.getKey();
            Stock stock = symbolMap.get(symbol);
            if (stock == null) {
                unmatched++;
                continue;
            }
            shortSaleOverseasRepository.upsertDaily(
                    stock.getId(), date, entry.getValue(), totalSums.get(symbol), now, null, null);
        }
        return new DailyLoadOutcome(skipped, unmatched);
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

    /** 하루치 처리 결과 — 파싱 skip 수·매칭 실패 수(REQ-BACKFILL-123 관측성). */
    private record DailyLoadOutcome(int skipped, int unmatched) {}
}
