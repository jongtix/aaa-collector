package com.aaa.collector.stock.rights;

import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * 종목지정 해외 현금배당 백필 fetch/persist 전담 협력자 (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001
 * REQ-ODW-050~055/060/070~072).
 *
 * <p>두 TR을 조합한다 — {@code rights-by-ice}(날짜 소스, {@link OverseasRightsCollectionService#fetch} 재사용)를
 * {@code from}~{@code to} 전체 범위를 고정 폭(기본 2년, {@link #BACKFILL_RIGHTS_CHUNK_MONTHS}) 서브윈도우로 나눠 순차
 * 호출하고(REQ-ODW-051 — 2026-08-07 실측으로 확인된 ~50건 경험적 상한 조용한 절단을 회피, spec.md §5.5), 인접 청크는 경계일 1일을
 * 중첩한다(REQ-ODW-051b — 경계 유실 방지, 중복은 persist 단계 INSERT IGNORE 멱등성이 흡수). {@code CTRGT011R}(금액 소스,
 * {@link DividendAmountPrefetcher#prefetchForBackfill})은 이미 커서 페이지네이션을 지원하므로 단일 광폭 범위 콜로 03·75를
 * 조회한다(REQ-ODW-052, 청킹 불필요). 호출자(외부 orchestration)에는 청킹이 노출되지 않는다 — {@code from}/{@code to}는 항상 전체
 * 백필 범위를 가리킨다(REQ-ODW-051).
 *
 * <p>매핑은 {@link OverseasRightsRowAccumulator#buildRow}(REQ-ODA-020~022, REQ-ODW-020~022 불변)를 재사용한다
 * — 백필 전용 매핑 로직을 신규로 만들지 않는다(REQ-ODW-055). PDNO 완전 일치 필터링(REQ-ODW-053)은 {@code
 * prefetchForBackfill}이 {@code trackedSymbols=Set.of(symbol)}로 좁혀 기존 {@code accumulateRow} 필터를
 * 재사용함으로써 보장된다.
 *
 * <p>[REQ-ODW-060] {@code rights-by-ice} 청크 중 하나라도, 또는 {@code CTRGT011R} 프리페치가 실패/절단되면 {@code
 * rawRowCount}를 낮게 조작해 폐기하지 않고 {@link OverseasDividendBackfillPrefetchFailedException}을 던져 재시도를
 * 유도한다 — 이미 성공한 다른 청크가 있어도 fetch 전체를 실패로 처리한다(부분 성공 상태로 COMPLETED 오판 방지).
 *
 * <p>{@link OverseasRightsCollectionService}와 별도 클래스로 분리한 이유(plan.md §C-3): 정기 수집(다중 종목 병렬·프리페치
 * 오케스트레이션)과 백필(단일 종목 청킹) 책임을 나눠 결합도를 낮춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasDividendBackfillService {

    /**
     * 백필 {@code rights-by-ice} 서브윈도우 청크 폭(개월, REQ-ODW-051a) — 2026-08-07 실측(SYMB=O, 월배당 REIT)으로 확인된
     * 경험적 상한(~50건) 대비 2배 이상의 안전 마진을 확보한다(관측 최고빈도 종목이 2년 구간에 약 24건 생성, spec.md §5.5 RD-5).
     */
    private static final long BACKFILL_RIGHTS_CHUNK_MONTHS = 24L;

    /**
     * 청크 1개당 절단 의심 임계(원본 {@code output1} 행수, 코드리뷰 W-1) — 2026-08-07 실측(SYMB=O)으로 확인된 경험적 상한(~50건)
     * 보다 낮게 잡아 조용한 절단이 실제로 발생하기 전에 fail-closed 한다(REQ-ODW-060 페이지 절단 트리거).
     *
     * <p>이 값은 {@code rights-by-ice}의 원본 {@code output1} 행수(전 권리유형 — 현금배당뿐 아니라 증자·상장폐지·상환 등 포함)에 대해
     * 검사한다. 응답이 현금배당 외 권리유형도 함께 반환하므로(§5.5 RD-5 대비 마진 계산은 배당 전용 빈도 기준), 실제 안전 마진은 클래스 상단 Javadoc의
     * "2배 이상"보다 낙관적일 수 있다 — 원본 행수 기준 검사가 그 낙관성을 상쇄한다.
     */
    private static final int BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS = 40;

    private final OverseasRightsCollectionService rightsCollectionService;
    private final DividendAmountPrefetcher dividendAmountPrefetcher;
    private final CorporateEventInserter corporateEventInserter;

    /**
     * [REQ-ODW-050/051/051a/051b/054] 종목지정 해외 현금배당 백필 fetch 단계 — 비트랜잭션, DB 미접촉.
     *
     * @param stock 백필 대상 종목
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param from 윈도우 하단 조회 시작일(공통 anchor, {@code windowAdvancer.groupAFromDate()})
     * @param to 윈도우 상단 조회 종료일(오늘 KST)
     * @return 적재 대상 엔티티 + 최소 event_date + rights-by-ice 전체 청크 원본 응답 행수 합산
     * @throws OverseasDividendBackfillPrefetchFailedException rights-by-ice 청크 또는 CTRGT011R 프리페치가
     *     실패/절단된 경우 — {@code BackfillOrchestrator}가 재시도(IN_PROGRESS 유지)하도록 전파한다
     */
    public OverseasDividendBackfillFetch fetchWindowForBackfill(
            Stock stock, LeaseSession session, LocalDate from, LocalDate to) {
        String symbol = stock.getSymbol();

        List<KisOverseasRightsResponse.RightsRow> rawRows =
                fetchAllChunks(symbol, session, from, to);

        DividendAmountPrefetch prefetch =
                dividendAmountPrefetcher.prefetchForBackfill(session, symbol, from, to);
        if (prefetch.prefetchTruncated() > 0 || prefetch.prefetchFailed() > 0) {
            log.warn(
                    "[overseas-dividend-backfill] CTRGT011R 백필 프리페치 실패/절단 — symbol={}, "
                            + "prefetchTruncated={}, prefetchFailed={}",
                    symbol,
                    prefetch.prefetchTruncated(),
                    prefetch.prefetchFailed());
            throw new OverseasDividendBackfillPrefetchFailedException(
                    "CTRGT011R 백필 프리페치 실패/절단 — symbol="
                            + symbol
                            + ", prefetchTruncated="
                            + prefetch.prefetchTruncated()
                            + ", prefetchFailed="
                            + prefetch.prefetchFailed());
        }

        OverseasRightsRowAccumulator accumulator = new OverseasRightsRowAccumulator();
        List<CorporateEvent> validRows = new ArrayList<>();
        for (KisOverseasRightsResponse.RightsRow row : rawRows) {
            accumulator.buildRow(row, stock, prefetch, validRows);
        }

        LocalDate oldest =
                validRows.stream()
                        .map(CorporateEvent::getEventDate)
                        .min(LocalDate::compareTo)
                        .orElse(null);

        OverseasRightsCollectionResult accResult = accumulator.toResult(1, prefetch);
        log.info(
                "[overseas-dividend-backfill] 백필 fetch 완료 — symbol={}, chunks={}, rawRowCount={}, "
                        + "validRows={}, skippedNonCashRows={}, skippedValidationRows={}, "
                        + "skippedUnconfirmed={}, skippedScripDividend={}",
                symbol,
                estimateChunkCount(from, to),
                rawRows.size(),
                validRows.size(),
                accResult.skippedNonCashRows(),
                accResult.skippedValidationRows(),
                accResult.skippedUnconfirmed(),
                accResult.skippedScripDividend());

        return new OverseasDividendBackfillFetch(validRows, oldest, rawRows.size());
    }

    /**
     * {@code from}~{@code to}를 {@link #BACKFILL_RIGHTS_CHUNK_MONTHS} 고정 폭 서브윈도우로 나눠 {@link
     * OverseasRightsCollectionService#fetch}를 순차 호출하고 전체 원본 행을 연결한다(REQ-ODW-051a).
     *
     * <p>청크 1개의 원본 응답 행수가 {@link #BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS} 이상이면 절단 의심으로
     * fail-closed 한다(REQ-ODW-060 페이지 절단 트리거, 코드리뷰 W-1).
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    private List<KisOverseasRightsResponse.RightsRow> fetchAllChunks(
            String symbol, LeaseSession session, LocalDate from, LocalDate to) {
        List<KisOverseasRightsResponse.RightsRow> rawRows = new ArrayList<>();
        int totalChunks = estimateChunkCount(from, to);
        int chunkIndex = 0;
        LocalDate chunkStart = from;
        while (!chunkStart.isAfter(to)) {
            chunkIndex++;
            LocalDate chunkEnd = chunkStart.plusMonths(BACKFILL_RIGHTS_CHUNK_MONTHS);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            List<KisOverseasRightsResponse.RightsRow> chunkRows =
                    fetchChunk(symbol, session, chunkStart, chunkEnd).output1();
            log.debug(
                    "[overseas-dividend-backfill] 청크 조회 완료 — symbol={}, chunk={}/{}, from={}, to={}, "
                            + "rows={}",
                    symbol,
                    chunkIndex,
                    totalChunks,
                    chunkStart,
                    chunkEnd,
                    chunkRows.size());
            if (chunkRows.size() >= BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS) {
                log.warn(
                        "[overseas-dividend-backfill] 절단 의심 — symbol={}, chunk={}/{}, from={}, to={}, "
                                + "rows={}, threshold={}",
                        symbol,
                        chunkIndex,
                        totalChunks,
                        chunkStart,
                        chunkEnd,
                        chunkRows.size(),
                        BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS);
                throw new OverseasDividendBackfillPrefetchFailedException(
                        "rights-by-ice 백필 청크 절단 의심(경험적 상한 ~50건 근접) — symbol="
                                + symbol
                                + ", chunkStart="
                                + chunkStart
                                + ", chunkEnd="
                                + chunkEnd
                                + ", rows="
                                + chunkRows.size()
                                + ", threshold="
                                + BACKFILL_RIGHTS_TRUNCATION_THRESHOLD_ROWS);
            }
            rawRows.addAll(chunkRows);

            // REQ-ODW-051b: 청크 N의 to == 청크 N+1의 from(양쪽 포함, 1일 중첩)
            if (!chunkEnd.isBefore(to)) {
                break;
            }
            chunkStart = chunkEnd;
        }
        return rawRows;
    }

    /** 진행 로그용 예상 청크 개수(정보성 — 실제 청킹 루프의 경계일 중첩과는 ±1 오차 가능). */
    private static int estimateChunkCount(LocalDate from, LocalDate to) {
        long totalMonths = Math.max(1, ChronoUnit.MONTHS.between(from, to));
        return (int) Math.max(1, Math.ceil((double) totalMonths / BACKFILL_RIGHTS_CHUNK_MONTHS));
    }

    /** 청크 1개를 조회하고, 실패/인터럽트를 rawRowCount 조작 없이 예외로 전파한다(REQ-ODW-060). */
    private KisOverseasRightsResponse fetchChunk(
            String symbol, LeaseSession session, LocalDate chunkStart, LocalDate chunkEnd) {
        try {
            return rightsCollectionService.fetch(session, symbol, chunkStart, chunkEnd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "[overseas-dividend-backfill] rights-by-ice 청크 인터럽트 — symbol={}, chunkStart={}, "
                            + "chunkEnd={}",
                    symbol,
                    chunkStart,
                    chunkEnd);
            throw new OverseasDividendBackfillPrefetchFailedException(
                    "rights-by-ice 백필 청크 인터럽트 — symbol="
                            + symbol
                            + ", chunkStart="
                            + chunkStart
                            + ", chunkEnd="
                            + chunkEnd,
                    e);
        } catch (KisRateLimitException
                | RestClientException
                | NoHealthyKeyException
                | KisTokenIssueException e) {
            log.warn(
                    "[overseas-dividend-backfill] rights-by-ice 청크 프리페치 실패 — symbol={}, "
                            + "chunkStart={}, chunkEnd={}, error={}",
                    symbol,
                    chunkStart,
                    chunkEnd,
                    e.getMessage());
            throw new OverseasDividendBackfillPrefetchFailedException(
                    "rights-by-ice 백필 청크 프리페치 실패 — symbol="
                            + symbol
                            + ", chunkStart="
                            + chunkStart
                            + ", chunkEnd="
                            + chunkEnd
                            + ", error="
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * [REQ-ODW-070/071/072] 종목지정 해외 현금배당 백필 persist 단계 — 적재 대상 엔티티를 INSERT IGNORE 적재한다.
     *
     * <p>{@code MANDATORY} 전파 — 활성 트랜잭션 없이 호출 시 즉시 실패한다. 트랜잭션은 {@code
     * BackfillWindowExecutor.routePersist}가 소유하며 이 메서드는 그 경계 안에서 호출된다({@link
     * OverseasSplitCollectionService#persistWindowForBackfill}과 동일 규약). {@link
     * CorporateEventInserter#insertBatch}(Tier-1 INSERT IGNORE, ON DUPLICATE KEY UPDATE 금지)로 멱등
     * 적재한다.
     *
     * @param fetch {@link #fetchWindowForBackfill}가 반환한 DTO
     * @return 종료 판정 입력(oldestTradeDate=최소 event_date, rowCount=저장 행수, rawRowCount=rights-by-ice 원본
     *     행수)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindowForBackfill(OverseasDividendBackfillFetch fetch) {
        corporateEventInserter.insertBatch(fetch.validRows());
        return new BackfillWindowResult(
                fetch.oldestRecordDate(), fetch.validRows().size(), fetch.rawRowCount());
    }
}
