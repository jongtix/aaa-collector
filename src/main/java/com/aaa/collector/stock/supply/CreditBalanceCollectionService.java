package com.aaa.collector.stock.supply;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
            saveValidRows(stock, symbol, response, today, windowStart);
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

    /** 검증·매핑·윈도우 필터·경계 커버리지 관측은 mapper에 위임하고, 결과 엔티티만 배치 삽입한다. */
    private void saveValidRows(
            Stock stock,
            String symbol,
            KisCreditBalanceResponse response,
            LocalDate today,
            LocalDate windowStart) {
        List<CreditBalance> validEntities =
                mapper.collectValid(stock, symbol, response, today, windowStart);
        if (validEntities.isEmpty()) {
            return;
        }
        inserter.insertBatch(validEntities);
    }
}
