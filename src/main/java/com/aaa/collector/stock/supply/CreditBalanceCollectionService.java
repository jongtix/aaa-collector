package com.aaa.collector.stock.supply;

import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillWindowResult;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.CreditBalance;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * 종목별 신용잔고 일별추이 수집 서비스 (TR FHPST04760000).
 *
 * <p>{@link InvestorTrendCollectionService}와 동일 골격. {@code fid_input_date_1}(결제일자) 단일 호출이 {@code
 * output}(<b>단수</b>) Object Array로 일자별 다건을 반환하여 14일 윈도우를 1회 호출에 충족한다(REQ-BATCH2-050).
 *
 * <p>[HARD] 날짜 매핑(REQ-BATCH2-052): {@code trade_date}에는 {@code deal_date}(매매일자)를 매핑한다 — {@code
 * stlm_date}(결제일자)가 아님. 만원 단위 무변환(REQ-BATCH2-053).
 *
 * <p>검증(REQ-BATCH2-060~063): 비율 절댓값 ≥ 1000(DECIMAL(7,4) 경계)·음수 수량/금액·null·파싱 실패 건별 skip. 14일 윈도우 밖
 * 행(deal_date 기준) 제외. 빈 응답은 0건 succeeded.
 *
 * <p>침묵 드롭(REQ-OBSV-023): 검증 통과 행들을 {@link CreditBalanceInserter}로 종목별 배치 삽입한다 — 인서터가 JDBC 경고 체인에서
 * 비-중복 드롭을 캡처하고 {@link com.aaa.collector.observability.BatchMetrics}에 기록한다. 본 서비스는 도메인 검증·매핑·skip
 * 집계만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 신용잔고 일별추이 수집 진입점 — 게이트 경유 키 lease·deal_date 매핑·비율 경계 검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-010~012,-050~053,-060~063,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024 — 게이트 경유 통합 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-002, SPEC-COLLECTOR-KISGATE-001
public class CreditBalanceCollectionService {

    static final int LOOKBACK_CALENDAR_DAYS = 14;

    /** 백필 수집 윈도우 캘린더 일수 — API 단일 호출이 ~30행(≈45달력일)을 반환하므로 전부 포착. */
    static final int BACKFILL_LOOKBACK_CALENDAR_DAYS = 45;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TR_ID = "FHPST04760000";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/daily-credit-balance";
    private static final String SCR_DIV_CODE = "20476";

    private final StockRepository stockRepository;
    private final CreditBalanceRowMapper mapper;
    private final CreditBalanceInserter inserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 신용잔고 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 자체 조회).
     *
     * @param today 수집 기준일(결제일자 파라미터로 사용)
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today) {
        return collect(today, stockRepository.findAllActiveTradable());
    }

    /**
     * 신용잔고 일별추이 수집을 실행하고 집계 결과를 반환한다 (활성종목 외부 주입 — 통합 진입점이 1회 조회를 공유).
     *
     * @param today 수집 기준일(결제일자 파라미터로 사용)
     * @param activeStocks 활성 관심종목 목록
     * @return 시도/성공/skip 종목 수 집계
     */
    public SupplyDemandResult collect(LocalDate today, List<Stock> activeStocks) {
        if (activeStocks.isEmpty()) {
            log.info("[credit-balance] 수집 대상 없음 — activeStocks=0");
            return new SupplyDemandResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[credit-balance] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new SupplyDemandResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션 공유(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () -> collectStock(stock, session, today, windowStart, succeeded, skipped));
            }
        }

        SupplyDemandResult result = new SupplyDemandResult(total, succeeded.get(), skipped.get());
        log.info(
                "[credit-balance] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisCreditBalanceResponse response = fetch(session, symbol, today);
            saveValidRows(stock, symbol, response, today, windowStart); // 반환값 무시 — 당일 경로
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip
            log.warn(
                    "[credit-balance] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip
            Thread.currentThread().interrupt();
            log.warn("[credit-balance] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            log.warn("[credit-balance] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[credit-balance] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private KisCreditBalanceResponse fetch(
            LeaseSession session, String symbol, LocalDate settlementDate)
            throws InterruptedException {
        String date = settlementDate.format(DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PATH)
                                .queryParam("fid_cond_mrkt_div_code", "J")
                                .queryParam("fid_cond_scr_div_code", SCR_DIV_CODE)
                                .queryParam("fid_input_iscd", symbol)
                                .queryParam("fid_input_date_1", date)
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, TR_ID, KisCreditBalanceResponse.class);
    }

    /**
     * 백필용 윈도우 1구간 수집 — 당일 수집과 동일한 매핑·검증·INSERT IGNORE 경로를 재사용한다 (REQ-BACKFILL-002).
     *
     * <p>TR FHPST04760000은 주말 anchor에도 {@code rt_cd="0"}을 반환한다(MA-05 실측 2026-06-20). anchor-skip 보정
     * 없음 — 단일 anchor 1회 호출. 종료 판정은 stale-count 기반으로 호출자(T5/T6)가 판단한다. 예외는 호출자가 상태 전이로 처리하도록 전파한다.
     *
     * @param stock 백필 대상 종목 (활성, REQ-BACKFILL-006)
     * @param session 호출자가 1회 고정한 per-run 헬스 스냅샷 세션
     * @param anchor 수집 기준일
     * @return 적재 대상 행의 최소 거래일 + 행 수 (적재 대상 없으면 {@link BackfillWindowResult#EMPTY})
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    public BackfillWindowResult collectWindow(Stock stock, LeaseSession session, LocalDate anchor)
            throws InterruptedException {
        LocalDate windowStart = anchor.minusDays(LOOKBACK_CALENDAR_DAYS);
        KisCreditBalanceResponse response = fetch(session, stock.getSymbol(), anchor);
        List<CreditBalance> validEntities =
                saveValidRows(stock, stock.getSymbol(), response, anchor, windowStart);
        if (validEntities.isEmpty()) {
            return BackfillWindowResult.EMPTY;
        }
        LocalDate oldest =
                validEntities.stream()
                        .map(CreditBalance::getTradeDate)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new BackfillWindowResult(oldest, validEntities.size());
    }

    /**
     * [T6] fetch 단계 — HTTP 호출·매핑·검증·윈도우 필터를 수행하고 INSERT하지 않는다.
     *
     * <p>{@code status.lastCollectedDate}를 anchor(= fid_input_date_1)로, anchor − 14 달력일을
     * windowStart로 삼아 KIS API를 호출한다(REQ-BACKFILL-050). anchor-skip 보정 없음 — FHPST04760000은 주말에도
     * rt_cd=0 반환(MA-05). 검증 통과 엔티티와 최소 deal_date를 {@link CreditBalanceFetch}로 반환한다. DB 접촉 없음.
     *
     * @param status 현재 백필 상태 (lastCollectedDate = anchor)
     * @param stock 백필 대상 종목
     * @param session 호출자가 고정한 per-run 헬스 스냅샷 세션
     * @return 검증 통과 엔티티 목록 + 최소 거래일 + 행 수 (없으면 rows=빈목록, oldestTradeDate=null, rowCount=0)
     * @throws InterruptedException 게이트 호출 인터럽트 시 전파
     */
    // @MX:NOTE: [AUTO] fetchWindow — 비tx HTTP 단계. DB 미접촉. BackfillWindowExecutor가 @Transactional
    // persistWindow와 교차 빈으로 순차 호출.
    public CreditBalanceFetch fetchWindow(BackfillStatus status, Stock stock, LeaseSession session)
            throws InterruptedException {
        LocalDate anchor = status.getLastCollectedDate();
        LocalDate windowStart = anchor.minusDays(BACKFILL_LOOKBACK_CALENDAR_DAYS);
        String symbol = stock.getSymbol();
        KisCreditBalanceResponse response = fetch(session, symbol, anchor);
        List<CreditBalance> validEntities =
                mapper.collectValid(stock, symbol, response, anchor, windowStart);
        if (validEntities.isEmpty()) {
            return new CreditBalanceFetch(List.of(), null, 0);
        }
        LocalDate oldest =
                validEntities.stream()
                        .map(CreditBalance::getTradeDate)
                        .min(Comparator.naturalOrder())
                        .orElseThrow();
        return new CreditBalanceFetch(validEntities, oldest, validEntities.size());
    }

    /**
     * [T6] persist 단계 — {@link CreditBalanceFetch}의 엔티티를 INSERT IGNORE 배치 적재한다.
     *
     * <p>@Transactional 없음 — 트랜잭션은 {@code BackfillWindowExecutor}(T7)가 소유한다(REQ-TXBOUNDARY-002).
     *
     * @param status 백필 상태 (anchor 로깅용)
     * @param stock 백필 대상 종목
     * @param fetch fetchWindow가 반환한 DTO
     * @return 적재 대상 행의 최소 거래일 + 행 수 (fetch가 빈 경우 {@link BackfillWindowResult#EMPTY})
     */
    // @MX:NOTE: [AUTO] persistWindow — BackfillWindowExecutor @Transactional에 MANDATORY 전파로 합류.
    // INSERT + 결과 구성 담당.
    @Transactional(propagation = Propagation.MANDATORY)
    public BackfillWindowResult persistWindow(
            BackfillStatus status, Stock stock, CreditBalanceFetch fetch) {
        if (fetch.rows().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "[credit-balance][backfill] persistWindow 스킵 (빈 fetch) — symbol={}, anchor={}",
                        stock.getSymbol(),
                        status.getLastCollectedDate());
            }
            return BackfillWindowResult.EMPTY;
        }
        inserter.insertBatch(fetch.rows());
        return new BackfillWindowResult(fetch.oldestTradeDate(), fetch.rowCount());
    }

    /**
     * 검증·매핑·윈도우 필터·경계 커버리지 관측은 mapper에 위임하고, 결과 엔티티를 배치 삽입한 뒤 반환한다.
     *
     * <p>당일 경로({@link #collectStock})는 반환값을 무시하고, 백필 경로({@link #collectWindow})는 최소 거래일·행 수 도출에
     * 사용한다 — 동일 매핑·검증·적재 경로를 공유한다(REQ-BACKFILL-002).
     *
     * @return 적재한 검증 통과 엔티티 목록 (없으면 빈 목록)
     */
    private List<CreditBalance> saveValidRows(
            Stock stock,
            String symbol,
            KisCreditBalanceResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<CreditBalance> validEntities =
                mapper.collectValid(stock, symbol, response, today, windowStart);
        if (validEntities.isEmpty()) {
            return validEntities;
        }
        inserter.insertBatch(validEntities);
        return validEntities;
    }
}
