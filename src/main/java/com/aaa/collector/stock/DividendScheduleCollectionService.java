package com.aaa.collector.stock;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * 배당 일정 수집 서비스 (TR HHKDB669102C0, SPEC-COLLECTOR-DIVIDEND-FIX-001).
 *
 * <p>활성 국내 관심종목({@link StockRepository#findAllActiveDomesticTradable()} — KOSPI/KOSDAQ/KRX ∩
 * STOCK/ETF)을 대상으로 종목별 루프를 구성한다(REQ-DIVFIX-010). 전체조회({@code SHT_CD=""}) + CTS 페이징을 사용하지 않는다 — 종목별
 * ±60일 윈도우가 단일 응답으로 완결되므로 100행 캡·본문 커서(옛 {@code output2.cts()}, 실측상 항상 부재)에 의존하지 않는다
 * (REQ-DIVFIX-011/012). 형제 {@link com.aaa.collector.stock.rights.OverseasRightsCollectionService}의
 * 종목별 /VT executor/LeaseSession 패턴을 그대로 이식했다.
 *
 * <p>호출은 단일 보호 게이트 {@link GuardedKisExecutor}를 경유한다(SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001). 배치
 * 시작 시 {@link KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정하고(REQ-DIVFIX-014), 모든 종목
 * 호출이 그 세션을 공유해 {@link GuardedKisExecutor}를 경유한다. 스냅샷이 비어(전 키 사망) 있으면 종목별 수집을 0회 수행하고 배치 전체를
 * skip한다(REQ-DIVFIX-040). 종목별 블로킹 호출은 {@link Executors#newVirtualThreadPerTaskExecutor()} 기반
 * Virtual Thread로 병렬 처리한다 — {@code parallelStream}(ForkJoinPool commonPool)을 사용하지
 * 않는다(REQ-DIVFIX-013).
 *
 * <p>종목별 조회는 관심종목만 질의하므로 비관심종목 행이 존재하지 않는다 — 관심종목 맵 필터·{@code skippedNonWatchlist} 집계는 소멸했다
 * (REQ-DIVFIX-015). 특정 종목 조회가 재시도 소진/토큰 실패/인터럽트로 실패하면 그 종목만 graceful skip하고 나머지 종목 수집을 계속한다
 * (REQ-DIVFIX-041).
 *
 * <p>EventType.DIVIDEND만 수집 — RIGHTS_ISSUE 제외 (REQ-BATCH3-054).
 *
 * <p>stream:daily:complete 미발행(REQ-BATCH3-011). 백필 미수행(REQ-BATCH3-012).
 *
 * <p>미확정(0/0) 배당 행은 {@link DividendRowAccumulator}가 record_date와 무관하게 무조건 defer한다(REQ-DIVFIX-020,
 * RD-6) — Tier-1 INSERT-only라 확정 전에는 채울 수 없어 확정까지 미룬다. rate-only 행({@code cash_amount==0} AND
 * {@code cash_rate!=0})은 defer 대상이 아니며 원본 그대로 저장한다(REQ-DIVFIX-032, RD-7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendScheduleCollectionService {

    private static final String TR_ID = "HHKDB669102C0";
    private static final String PATH = "/uapi/domestic-stock/v1/ksdinfo/dividend";

    /**
     * KIS 예탁원정보 배당일정 전체조회 응답당 최대 행 수(구조적 상한). 종목지정 백필에서 이 값에 도달하면 GROUP_A 종료 조건(100행 미만)이 미충족되어 그
     * 종목이 다음 회차로 이월된다(REQ-BACKFILL-135). [SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-011/REQ-GC-006]
     * 옛 RD-2 주석("다음 회차 이월은 GROUP_A 종료 정책이 담당한다")은 실제로는 {@code T_DT=today} 고정 버그로 인해 이월이 작동하지
     * 않았다(anchor 전진 없는 동일 쿼리 반복) — {@code T_DT}를 anchor로 전환해 실제로 이월이 동작하도록 복구했다.
     */
    private static final int MAX_ROWS_PER_PAGE = 100;

    /** LocalDate → yyyyMMdd(F_DT/T_DT) 변환 포맷 — 백필 전기간 윈도우 전달용. */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final StockRepository stockRepository;
    private final CorporateEventRepository corporateEventRepository;
    private final CorporateEventInserter corporateEventInserter;

    /**
     * 배당 일정 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param fromDate 조회 시작일 (yyyyMMdd)
     * @param toDate 조회 종료일 (yyyyMMdd)
     * @return attempted/succeeded/skipped 행 수 집계
     */
    public DividendCollectionResult collect(String fromDate, String toDate) {
        // REQ-DIVFIX-010: 활성 국내 관심종목만 대상 — 전체조회(SHT_CD="") 사용하지 않음
        List<Stock> activeStocks = stockRepository.findAllActiveDomesticTradable();

        if (activeStocks.isEmpty()) {
            log.info("[dividend] 수집 대상 없음 — activeStocks=0");
            return new DividendCollectionResult(0, 0, 0, 0);
        }

        // REQ-DIVFIX-014: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-DIVFIX-040: 빈 스냅샷 = 전 키 사망 → per-stock 수집 0회, 전체 skip, ERROR
        if (session.isEmpty()) {
            log.error("[dividend] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attemptedStocks={}", total);
            return new DividendCollectionResult(0, 0, 0, 0);
        }

        AtomicInteger attempted = new AtomicInteger();
        AtomicInteger succeeded = new AtomicInteger();
        // REQ-INSERT-011: 독성 행(DataAccessException) skip 수 — 검증 skip과 합산해
        // result.skippedValidation()에 반영
        AtomicInteger toxicFailures = new AtomicInteger();
        // REQ-DIVFIX-020~022, 030~032: 검증·미확정(0/0) defer 판정·매핑·카운팅을 전담하는 협력자(배치당 1개, 스레드 공유)
        DividendRowAccumulator accumulator = new DividendRowAccumulator();

        // REQ-DIVFIX-013: Virtual Thread executor — 종목별 블로킹을 commonPool 점유 없이 처리. parallelStream
        // 금지.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () ->
                                collectStock(
                                        stock,
                                        session,
                                        fromDate,
                                        toDate,
                                        attempted,
                                        succeeded,
                                        toxicFailures,
                                        accumulator));
            }
        } // close() blocks until all submitted tasks complete

        // REQ-DIVFIX-050: skippedUnconfirmed는 accumulator가 집계한 실제 defer 행 수를 그대로 결과 레코드에 반영한다.
        int skippedValidation = accumulator.skippedValidation() + toxicFailures.get();
        DividendCollectionResult result =
                new DividendCollectionResult(
                        attempted.get(),
                        succeeded.get(),
                        accumulator.skippedUnconfirmed(),
                        skippedValidation);
        log.info(
                "[dividend] 수집 완료 — attemptedStocks={}, attempted={}, succeeded={}, "
                        + "skippedValidation={}, skippedUnconfirmed={}",
                total,
                result.attempted(),
                result.succeeded(),
                result.skippedValidation(),
                result.skippedUnconfirmed());
        return result;
    }

    /** 종목별 배당 일정을 조회·매핑·저장한다(REQ-DIVFIX-011, REQ-DIVFIX-041 예외 격리). */
    private void collectStock(
            Stock stock,
            LeaseSession session,
            String fromDate,
            String toDate,
            AtomicInteger attempted,
            AtomicInteger succeeded,
            AtomicInteger toxicFailures,
            DividendRowAccumulator accumulator) {

        String symbol = stock.getSymbol();
        try {
            KisDividendScheduleResponse response = fetch(session, symbol, fromDate, toDate);

            // rt_cd=0이어도 output1 비면 종목 skip
            if (response.output1().isEmpty()) {
                return;
            }

            // REQ-INSERT-011: 유효 행 누적 후 격리 삽입 — 검증·미확정 defer 판정은 accumulator에 위임
            List<CorporateEvent> batch = new ArrayList<>();
            for (KisDividendScheduleResponse.DividendRow row : response.output1()) {
                attempted.incrementAndGet();
                accumulator.buildRow(row, stock, batch);
            }

            if (!batch.isEmpty()) {
                AtomicInteger dbFailures = new AtomicInteger();
                corporateEventInserter.insertBatchIsolated(
                        batch,
                        (entity, ex) -> {
                            log.warn(
                                    "[dividend] 행 저장 실패 — skip (stock={}, error={})",
                                    entity.getStock().getSymbol(),
                                    ex.getMessage());
                            dbFailures.incrementAndGet();
                        });
                int failures = dbFailures.get();
                succeeded.addAndGet(batch.size() - failures);
                toxicFailures.addAndGet(failures);
            }
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-DIVFIX-041: retryable 재시도 소진 → graceful skip
            log.warn("[dividend] skip (재시도 소진) — symbol={}, reason={}", symbol, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[dividend] 인터럽트 — symbol={} skip", symbol);
        } catch (NoHealthyKeyException e) {
            // 방어적: collect()에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[dividend] 건강 키 0개로 skip — symbol={}", symbol);
        } catch (KisTokenIssueException e) {
            // REQ-DIVFIX-041: token 발급 실패 → graceful skip
            log.warn("[dividend] 토큰 발급 실패로 skip — symbol={}, error={}", symbol, e.getMessage());
        }
    }

    /** 게이트를 경유해 종목별 배당 일정을 조회한다(REQ-DIVFIX-011 — SHT_CD=종목코드, CTS=공백 고정). */
    private KisDividendScheduleResponse fetch(
            LeaseSession session, String symbol, String fromDate, String toDate)
            throws InterruptedException {
        return guardedKisExecutor.execute(
                session,
                uri ->
                        uri.path(PATH)
                                .queryParam("CTS", "")
                                .queryParam("GB1", "0")
                                .queryParam("F_DT", fromDate)
                                .queryParam("T_DT", toDate)
                                .queryParam("SHT_CD", symbol)
                                .queryParam("HIGH_GB", "")
                                .build(),
                TR_ID,
                KisDividendScheduleResponse.class);
    }

    /**
     * [SPEC-COLLECTOR-BACKFILL-009 W2] 종목지정 현금배당 백필 fetch 단계 — 비트랜잭션, DB 미접촉.
     *
     * <p>국내 일봉 백필의 {@code fetchWindow}/{@code persistWindow} 분리 패턴(BackfillWindowExecutor 계약)에 맞춘다.
     * {@code SHT_CD=<종목코드>}(전체조회 금지, REQ-BACKFILL-127/128)·{@code F_DT=<플로어>}·{@code T_DT=<today>}로
     * 1회 호출(REQ-BACKFILL-124/126)하고, 라이브 수집이 확정한 저장 정책({@link DividendRowAccumulator} — 0/0 defer
     * RD-6· rate-only 원본 저장 RD-7·역산 미수행 RD-3)을 그대로 재사용해 적재 대상 엔티티를 구성한다 — 백필 전용 저장 정책을 신설하지
     * 않는다(REQ-BACKFILL-129~131).
     *
     * <p>[HARD] REQ-BACKFILL-135: 종료 판정 입력 {@code rawRowCount}는 KIS {@code output1} 원본 행수(defer/검증
     * skip 전)로 고정한다. 원본 행수가 100행 캡에 도달하면(GROUP_A 종료 조건 미충족) 경고 로그를 남긴다 — 정교한 기간분할 재조회는 구현하지 않으며(§3
     * Exclusions), 다음 회차 이월은 GROUP_A 종료 정책(rawRowCount≥100 → IN_PROGRESS 유지)이 담당한다. [
     * SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-011] 이월이 실제로 성립하려면 다음 회차 호출의 {@code T_DT}가
     * anchor(윈도우 진행점, {@code resolvedStatus.getLastCollectedDate()})여야 한다 — 호출자({@link
     * com.aaa.collector.stock.backfill.BackfillWindowExecutor#routeFetch})가 이 계약을 지킨다.
     *
     * <p>정기 수집({@link #collect})과 달리 관심종목 필터·종목별 예외 격리를 이 메서드가 직접 수행하지 않는다 — 호출자(백필 오케스트레이터)가 종목을
     * 지정하고 status 단위로 예외를 격리한다(REQ-BACKFILL-138). 인터럽트 수신 시 플래그를 복원한 뒤 {@link
     * IllegalStateException}으로 전파한다(RevSplit 백필 fetch 동형).
     *
     * @param stock 백필 대상 종목 (시딩된 corporate_events_dividend status의 target)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param from 윈도우 하단 조회 시작일 (F_DT, 고정 플로어)
     * @param to 윈도우 상단 조회 종료일 (T_DT, anchor — SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-011, 옛
     *     today 고정 아님)
     * @return 적재 대상 엔티티 + 원본 응답 전체 최소 record_date(REQ-GC-012) + 원본 응답 행수
     */
    // @MX:NOTE: [AUTO] 종목지정 배당 백필 fetch — 비tx HTTP 단계. BackfillWindowExecutor가 @Transactional
    // persistWindowForBackfill과 교차 빈으로 순차 호출. 저장 정책은 DividendRowAccumulator 재사용(0/0 defer·rate-only
    // 저장).
    public DividendBackfillFetch fetchWindowForBackfill(
            Stock stock, LeaseSession session, LocalDate from, LocalDate to) {
        KisDividendScheduleResponse response;
        try {
            response =
                    fetch(session, stock.getSymbol(), from.format(DATE_FMT), to.format(DATE_FMT));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("배당 백필 조회 중 인터럽트 — symbol=" + stock.getSymbol(), ie);
        }

        List<KisDividendScheduleResponse.DividendRow> rows = response.output1();

        // REQ-BACKFILL-135: 종료 판정 입력 = KIS output1 원본 행수 (defer/검증 skip 전, 결정적)
        int rawRowCount = rows.size();

        // REQ-BACKFILL-135: 100행 캡 도달 시 경고 로그 + 다음 회차 이월(GROUP_A 종료 정책이 담당).
        // 정교한 기간분할 재조회는 미구현 — 실측상 100행 캡 도달 종목 전무(§3 Exclusions).
        // REQ-GC-011: 이월은 다음 회차 T_DT=anchor 전환으로 실제 동작한다(옛 T_DT=today 고정 버그 수정).
        if (rawRowCount >= MAX_ROWS_PER_PAGE) {
            log.warn(
                    "[dividend-backfill] {}행 캡 도달 (symbol={}, records={}) — 다음 회차 이월(T_DT=anchor),"
                            + " 기간분할 미구현",
                    MAX_ROWS_PER_PAGE,
                    stock.getSymbol(),
                    rawRowCount);
        }

        // 저장 정책 재사용(REQ-BACKFILL-129~131): 0/0 defer·rate-only 저장·역산 미수행을 accumulator가 수행
        DividendRowAccumulator accumulator = new DividendRowAccumulator();
        List<CorporateEvent> validRows = new ArrayList<>();
        for (KisDividendScheduleResponse.DividendRow row : rows) {
            accumulator.buildRow(row, stock, validRows);
        }

        // SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-012: anchor 전진 입력 = 원본 응답 전체(defer 이전)의 최소
        // record_date — 저장 대상(validRows)만 보면 최고(最古) 행이 0/0 defer됐을 때 무전진 오판이 발생한다.
        LocalDate rawOldest = rawOldestRecordDate(rows);

        // REQ-BACKFILL-140: 침묵 드롭 없이 진행 관측 — 원본/저장/defer 행수를 남긴다(rt_cd만으로 판단 금지).
        log.info(
                "[dividend-backfill] 종목 처리 — symbol={}, rawRows={}, stored={}, deferred00={}",
                stock.getSymbol(),
                rawRowCount,
                validRows.size(),
                accumulator.skippedUnconfirmed());

        return new DividendBackfillFetch(validRows, rawOldest, rawRowCount);
    }

    /**
     * 원본 응답 전체(defer/검증 skip 적용 전) 행들의 최소 {@code record_date}를 산정한다
     * (SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-012). 파싱 실패(null/blank/형식 오류) 행은 제외한다 — 파싱 가능한 원본
     * 행 중 최소값이다.
     */
    private static LocalDate rawOldestRecordDate(
            List<KisDividendScheduleResponse.DividendRow> rows) {
        LocalDate oldest = null;
        for (KisDividendScheduleResponse.DividendRow row : rows) {
            LocalDate parsed = parseRecordDateOrNull(row.recordDate());
            if (parsed != null && (oldest == null || parsed.isBefore(oldest))) {
                oldest = parsed;
            }
        }
        return oldest;
    }

    private static LocalDate parseRecordDateOrNull(String recordDate) {
        if (recordDate == null || recordDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(recordDate, DATE_FMT);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * [SPEC-COLLECTOR-BACKFILL-009 W2] 종목지정 현금배당 백필 persist 단계 — 적재 대상 엔티티를 INSERT IGNORE 적재한다.
     *
     * <p>{@code MANDATORY} 전파 — 활성 트랜잭션 없이 호출 시 {@code IllegalTransactionStateException}이 발생한다.
     * 트랜잭션은 {@code BackfillWindowExecutor.persistWindow}가 소유하며 이 메서드는 그 경계 안에서 호출된다({@link
     * RevSplitCollectionService#persistWindowForBackfill} 동일 패턴). {@link
     * CorporateEventInserter#insertBatch} (Tier-1 INSERT IGNORE, {@code ON DUPLICATE KEY UPDATE}
     * 금지, REQ-BACKFILL-132)로 멱등 적재한다 — 라이브 수집분과 중복 시 4컬럼 unique key {@code (stock_id, event_type,
     * event_date, event_subtype)}로 조용히 무시된다.
     *
     * <p>종료 판정 입력 {@link BackfillWindowResult#rawRowCount()}는 {@code rawRowCount}(KIS 원본 응답 행수,
     * skip 전, REQ-BACKFILL-135), 저장 행수 {@link BackfillWindowResult#rowCount()}는 적재 대상 {@code
     * validRows.size()}로 분리한다. {@code oldestTradeDate}는 {@link
     * DividendBackfillFetch#rawOldestRecordDate()}(원본 응답 전체 최소 record_date, defer 이전) —
     * SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-012: anchor 전진이 0/0 defer 여부와 무관하게 일어나도록 한다.
     *
     * @param fetch {@link #fetchWindowForBackfill}가 반환한 DTO
     * @return 종료 판정 입력 (oldestTradeDate=원본 최소 record_date, rowCount=저장 행수, rawRowCount=원본 응답 행수)
     */
    // @MX:NOTE: [AUTO] 종목지정 배당 백필 persist — BackfillWindowExecutor @Transactional 경계에서 호출.
    // INSERT IGNORE 적재 후 3-arg BackfillWindowResult(rowCount=validRows.size, rawRowCount=원본) 반환.
    // MANDATORY 가드 — tx 없이 호출 시 즉시 실패.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindowForBackfill(DividendBackfillFetch fetch) {
        corporateEventInserter.insertBatch(fetch.validRows());
        return new BackfillWindowResult(
                fetch.rawOldestRecordDate(), fetch.validRows().size(), fetch.rawRowCount());
    }
}
