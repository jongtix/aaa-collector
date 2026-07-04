package com.aaa.collector.stock;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
}
