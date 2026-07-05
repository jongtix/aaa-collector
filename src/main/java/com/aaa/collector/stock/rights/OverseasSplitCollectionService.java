package com.aaa.collector.stock.rights;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.OverseasSplitPrefetch;
import com.aaa.collector.stock.rights.OverseasSplitPrefetcher.TypeResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 해외(미국) 액면분할·병합(SPLIT) 수집 서비스 (TR CTRGT011R {@code RGHT_TYPE_CD ∈ {14, 15}},
 * SPEC-COLLECTOR-OVERSEAS-SPLIT-001).
 *
 * <p>정기 수집({@link #collect()})은 (1) 미장 개장일 게이트({@link UsMarketOpenGate}) → (2) 활성 미국 추적 종목 조회 → (3)
 * per-batch lease 세션 1회 고정 → (4) {@link OverseasSplitPrefetcher}로 14/15 전체조회 커서 페이징 → (5) {@link
 * OverseasSplitMapper}로 dedup·÷100 정규화·매핑 → (6) {@link CorporateEventInserter} Tier-1 INSERT IGNORE
 * 적재 순으로 진행한다.
 *
 * <p>fail-closed(REQ-OSPLIT-070/071): 세션이 비어(전 키 사망) 페이징 개시 전 전역 실패면 이번 배치에서 SPLIT 행을 하나도 만들지 않는다.
 * 유형(14/15) 단위 절단/실패는 그 유형만 폐기하고 다른 유형의 완전한 결과는 그대로 적재한다(프리페처가 유형 격리 보장).
 *
 * <p>백필({@link #fetchWindowForBackfill}/{@link #persistWindowForBackfill})은 종목지정 광폭 윈도우로 정기 수집과 동일
 * dedup·정규화·매핑 규약을 재사용한다(REQ-OSPLIT-060/061, W5 참조 — 별도 커밋).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 해외 SPLIT 수집 진입점 — 게이트·프리페처·매퍼·인서터가 수렴하는 fan-in 경계
// @MX:REASON: [AUTO] 스케줄러 정기 수집 + BackfillWindowExecutor 백필 dispatch 양쪽에서 호출(fan_in >= 3 예상)
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-SPLIT-001
public class OverseasSplitCollectionService {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 정기 수집 슬라이딩 윈도우 — 근과거 look-back(원거리 과거는 백필 담당, plan.md M1). */
    private static final long WINDOW_LOOKBACK_MONTHS = 1L;

    /** 정기 수집 슬라이딩 윈도우 — 분할은 사전 공지되므로 forward-leaning(plan.md M1). */
    private static final long WINDOW_FORWARD_MONTHS = 3L;

    /** KST/ET 기준시각 비대칭 흡수용 경계 패딩. */
    private static final long WINDOW_PADDING_DAYS = 1L;

    private final StockRepository stockRepository;
    private final CorporateEventInserter corporateEventInserter;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final UsMarketOpenGate usMarketOpenGate;
    private final OverseasSplitPrefetcher prefetcher;
    private final OverseasSplitMapper mapper;

    /**
     * 해외 분할·병합 정기 수집을 실행하고 집계 결과를 반환한다(REQ-OSPLIT-001/002/003/010~013/070/071).
     *
     * @return 저장 행/skip/프리페치 집계
     */
    public OverseasSplitCollectionResult collect() {
        // REQ-OSPLIT-003: NY 기준 오늘이 미장 휴장일 → skip
        LocalDate nyToday = LocalDate.now(NEW_YORK);
        if (!usMarketOpenGate.isOpenDay(nyToday)) {
            log.info("[overseas-split] {} 미장 휴장일(NY 기준) → skip", nyToday);
            return OverseasSplitCollectionResult.empty();
        }

        // REQ-OSPLIT-020/021: 활성 미국 STOCK+ETF 추적 종목 집합
        List<Stock> activeStocks = stockRepository.findAllActiveOverseasTradable();
        if (activeStocks.isEmpty()) {
            log.info("[overseas-split] 수집 대상 없음 — activeStocks=0");
            return OverseasSplitCollectionResult.empty();
        }
        Map<String, Stock> trackedStockBySymbol = buildTrackedMap(activeStocks);

        // per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        if (session.isEmpty()) {
            // REQ-OSPLIT-070: 전 키 사망 → 페이징 개시 전 전역 실패, 이번 배치 SPLIT 행 0건
            log.error("[overseas-split] 모든 키 죽음 — 페이징 개시 전 전역 실패, 이번 배치 skip");
            return OverseasSplitCollectionResult.globalFailClosed();
        }

        // REQ-OSPLIT-010: 전체조회(PDNO 공백) + 슬라이딩 윈도우(경계 패딩)
        String startDate =
                nyToday.minusMonths(WINDOW_LOOKBACK_MONTHS)
                        .minusDays(WINDOW_PADDING_DAYS)
                        .format(DATE_FMT);
        String endDate =
                nyToday.plusMonths(WINDOW_FORWARD_MONTHS)
                        .plusDays(WINDOW_PADDING_DAYS)
                        .format(DATE_FMT);
        OverseasSplitPrefetch prefetch = prefetcher.prefetch(session, "", startDate, endDate);

        SplitAccumulator acc = new SplitAccumulator();
        processType(
                prefetch.split(), OverseasSplitMapper.RGHT_TYPE_SPLIT, trackedStockBySymbol, acc);
        processType(
                prefetch.merge(), OverseasSplitMapper.RGHT_TYPE_MERGE, trackedStockBySymbol, acc);

        OverseasSplitCollectionResult result = acc.toResult();
        log.info(
                "[overseas-split] 수집 완료 — succeededRows={}, skippedUnconfirmed={}, skippedUntracked={}, "
                        + "skippedUnparsableDate={}, skippedNoWeekday={}, skippedInvalidRate={}, "
                        + "skippedDbFailure={}, prefetchTruncated={}, prefetchFailed={}",
                result.succeededRows(),
                result.skippedUnconfirmed(),
                result.skippedUntracked(),
                result.skippedUnparsableDate(),
                result.skippedNoWeekday(),
                result.skippedInvalidRate(),
                result.skippedDbFailure(),
                result.prefetchTruncated(),
                result.prefetchFailed());
        return result;
    }

    /**
     * [REQ-OSPLIT-060/061] 종목지정 해외 SPLIT 백필 fetch 단계 — 비트랜잭션, DB 미접촉.
     *
     * <p>국내 {@link com.aaa.collector.stock.RevSplitCollectionService#fetchWindowForBackfill}와 동일 규약
     * (BackfillWindowExecutor 계약). {@code PDNO=<심볼>}·광폭 과거 윈도우로 14/15를 조회하고, 정기 수집과 동일한 dedup·÷100
     * 정규화·매핑 규약({@link OverseasSplitMapper})을 재사용해 적재 대상 엔티티를 구성한다 — 매핑 로직 중복 없음(REQ-OSPLIT-061).
     *
     * <p>종료 판정 입력 {@code rawRowCount}는 CTRGT011R 14+15 원본 응답 행수(dedup 전)로 고정하고, 실제 적재 행수({@code
     * validRows.size()})와 결정적으로 분리한다. 정기 수집과 달리 추적 종목 필터 집합은 호출자가 지정한 단일 종목이다.
     *
     * @param stock 백필 대상 종목 (corporate_events status의 target)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param from 윈도우 하단 조회 시작일 (고정 플로어)
     * @param to 윈도우 상단 조회 종료일 (today)
     * @return 적재 대상 엔티티 + 최소 event_date + 원본 응답 행수
     */
    // @MX:NOTE: [AUTO] 종목지정 SPLIT 백필 fetch — 비tx HTTP 단계. BackfillWindowExecutor가 @Transactional
    // persistWindowForBackfill과 교차 빈으로 순차 호출. rawRowCount=14+15 원본 행수(dedup 전).
    public OverseasSplitBackfillFetch fetchWindowForBackfill(
            Stock stock, LeaseSession session, LocalDate from, LocalDate to) {
        Map<String, Stock> trackedStockBySymbol = new LinkedHashMap<>();
        trackedStockBySymbol.put(stock.getSymbol(), stock);

        OverseasSplitPrefetch prefetch =
                prefetcher.prefetch(
                        session, stock.getSymbol(), from.format(DATE_FMT), to.format(DATE_FMT));

        List<CorporateEvent> validRows = new ArrayList<>();
        int rawRowCount = 0;
        rawRowCount +=
                mapBackfillType(
                        prefetch.split(),
                        OverseasSplitMapper.RGHT_TYPE_SPLIT,
                        trackedStockBySymbol,
                        validRows);
        rawRowCount +=
                mapBackfillType(
                        prefetch.merge(),
                        OverseasSplitMapper.RGHT_TYPE_MERGE,
                        trackedStockBySymbol,
                        validRows);

        LocalDate oldest =
                validRows.stream()
                        .map(CorporateEvent::getEventDate)
                        .min(LocalDate::compareTo)
                        .orElse(null);
        return new OverseasSplitBackfillFetch(validRows, oldest, rawRowCount);
    }

    /**
     * 백필 한 유형(14/15)의 SUCCESS 원본 행을 매핑해 {@code validRows}에 누적하고 원본 행수를 반환한다. TRUNCATED/FAILED는
     * fail-closed로 폐기(0건 기여).
     *
     * @return 이 유형의 원본 응답 행수(SUCCESS만, 종료 판정 입력)
     */
    private int mapBackfillType(
            TypeResult typeResult,
            String rghtTypeCd,
            Map<String, Stock> trackedStockBySymbol,
            List<CorporateEvent> validRows) {
        if (typeResult.status() != OverseasSplitPrefetcher.PrefetchStatus.SUCCESS) {
            return 0;
        }
        OverseasSplitMapper.MapResult mapped =
                mapper.mapRows(typeResult.rows(), rghtTypeCd, trackedStockBySymbol);
        validRows.addAll(mapped.events());
        return typeResult.rows().size();
    }

    /**
     * [REQ-OSPLIT-060] 종목지정 해외 SPLIT 백필 persist 단계 — 적재 대상 엔티티를 INSERT IGNORE 적재한다.
     *
     * <p>{@code MANDATORY} 전파 — 활성 트랜잭션 없이 호출 시 {@code IllegalTransactionStateException}이 발생한다.
     * 트랜잭션은 {@code BackfillWindowExecutor.routePersist}가 소유하며 이 메서드는 그 경계 안에서 호출된다(국내 {@link
     * com.aaa.collector.stock.RevSplitCollectionService#persistWindowForBackfill} 동일 패턴). {@link
     * CorporateEventInserter#insertBatch}(Tier-1 INSERT IGNORE, ON DUPLICATE KEY UPDATE 금지)로 멱등
     * 적재한다.
     *
     * @param fetch {@link #fetchWindowForBackfill}가 반환한 DTO
     * @return 종료 판정 입력 (oldestTradeDate=최소 event_date, rowCount=저장 행수, rawRowCount=원본 응답 행수)
     */
    // @MX:NOTE: [AUTO] 종목지정 SPLIT 백필 persist — BackfillWindowExecutor @Transactional 경계에서 호출.
    // INSERT IGNORE 적재 후 3-arg BackfillWindowResult(rowCount=validRows.size, rawRowCount=원본) 반환.
    // MANDATORY 가드 — tx 없이 호출 시 즉시 실패.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindowForBackfill(OverseasSplitBackfillFetch fetch) {
        corporateEventInserter.insertBatch(fetch.validRows());
        return new BackfillWindowResult(
                fetch.oldestRecordDate(), fetch.validRows().size(), fetch.rawRowCount());
    }

    /**
     * 한 유형(14/15)의 프리페치 결과를 매핑·적재하거나(SUCCESS), 폐기하며 fail-closed 카운터를 올린다(TRUNCATED/FAILED,
     * REQ-OSPLIT-071).
     */
    private void processType(
            TypeResult typeResult,
            String rghtTypeCd,
            Map<String, Stock> trackedStockBySymbol,
            SplitAccumulator acc) {
        switch (typeResult.status()) {
            case SUCCESS -> {
                OverseasSplitMapper.MapResult mapped =
                        mapper.mapRows(typeResult.rows(), rghtTypeCd, trackedStockBySymbol);
                acc.addMapResult(mapped);
                insert(mapped.events(), acc);
            }
            case TRUNCATED -> acc.recordTruncated();
            case FAILED -> acc.recordFailed();
        }
    }

    /** 매핑된 이벤트를 Tier-1 INSERT IGNORE로 격리 적재하고 저장/실패 행수를 집계한다(REQ-OSPLIT-050/051). */
    private void insert(List<CorporateEvent> events, SplitAccumulator acc) {
        if (events.isEmpty()) {
            return;
        }
        AtomicInteger dbFailures = new AtomicInteger();
        corporateEventInserter.insertBatchIsolated(
                events,
                (entity, ex) -> {
                    log.warn(
                            "[overseas-split] 행 저장 실패 — skip (symbol={}, error={})",
                            entity.getStock().getSymbol(),
                            ex.getMessage());
                    dbFailures.incrementAndGet();
                });
        acc.recordInserted(events.size() - dbFailures.get(), dbFailures.get());
    }

    /** 활성 미국 추적 종목을 {@code symbol → Stock} 맵으로 구성. keySet이 추적 심볼 집합(REQ-OSPLIT-021). */
    @SuppressWarnings("PMD.UseConcurrentHashMap") // 단일 스레드 빌드 후 read-only
    private Map<String, Stock> buildTrackedMap(List<Stock> activeStocks) {
        Map<String, Stock> map = new LinkedHashMap<>();
        for (Stock stock : activeStocks) {
            map.put(stock.getSymbol(), stock);
        }
        return map;
    }

    /** 14/15 유형 처리 결과를 누적하는 가변 집계자(단일 스레드). */
    private static final class SplitAccumulator {
        private int succeededRows;
        private int skippedUnconfirmed;
        private int skippedUntracked;
        private int skippedUnparsableDate;
        private int skippedNoWeekday;
        private int skippedInvalidRate;
        private int skippedDbFailure;
        private int prefetchTruncated;
        private int prefetchFailed;

        void addMapResult(OverseasSplitMapper.MapResult mapped) {
            skippedUnconfirmed += mapped.skippedUnconfirmed();
            skippedUntracked += mapped.skippedUntracked();
            skippedUnparsableDate += mapped.skippedUnparsableDate();
            skippedNoWeekday += mapped.skippedNoWeekday();
            skippedInvalidRate += mapped.skippedInvalidRate();
        }

        void recordInserted(int succeeded, int dbFailures) {
            succeededRows += succeeded;
            skippedDbFailure += dbFailures;
        }

        void recordTruncated() {
            prefetchTruncated++;
        }

        void recordFailed() {
            prefetchFailed++;
        }

        OverseasSplitCollectionResult toResult() {
            return new OverseasSplitCollectionResult(
                    succeededRows,
                    skippedUnconfirmed,
                    skippedUntracked,
                    skippedUnparsableDate,
                    skippedNoWeekday,
                    skippedInvalidRate,
                    skippedDbFailure,
                    prefetchTruncated,
                    prefetchFailed);
        }
    }

    /**
     * 해외 SPLIT 정기 수집 집계 결과.
     *
     * @param succeededRows INSERT IGNORE 적재 성공 행 수
     * @param skippedUnconfirmed 미확정(dfnt_yn≠Y) skip 행 수
     * @param skippedUntracked 비추적 종목 skip 행 수
     * @param skippedUnparsableDate bass_dt 파싱 실패 skip 행 수
     * @param skippedNoWeekday 유효 평일 bass_dt 부재로 skip된 이벤트 수(REQ-OSPLIT-032)
     * @param skippedInvalidRate stck_alct_rt 파싱 실패·경계 초과 skip 이벤트 수(REQ-OSPLIT-043)
     * @param skippedDbFailure INSERT 격리 실패 행 수
     * @param prefetchTruncated MAX_PAGES 절단으로 폐기된 유형 수(0~2)
     * @param prefetchFailed 페이징 실패/전역 실패로 폐기된 유형 수(0~2)
     */
    public record OverseasSplitCollectionResult(
            int succeededRows,
            int skippedUnconfirmed,
            int skippedUntracked,
            int skippedUnparsableDate,
            int skippedNoWeekday,
            int skippedInvalidRate,
            int skippedDbFailure,
            int prefetchTruncated,
            int prefetchFailed) {

        static OverseasSplitCollectionResult empty() {
            return new OverseasSplitCollectionResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** 전 키 사망 등 페이징 개시 전 전역 실패 — 두 유형 모두 폐기(REQ-OSPLIT-070). */
        static OverseasSplitCollectionResult globalFailClosed() {
            return new OverseasSplitCollectionResult(0, 0, 0, 0, 0, 0, 0, 0, 2);
        }
    }
}
