package com.aaa.collector.stock.rights;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * 해외 현금배당 수집 서비스 (TR HHDFS78330900, SPEC-COLLECTOR-OVERSEAS-ETC-001 +
 * SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001).
 *
 * <p>활성 미국(NYSE/NASDAQ/AMEX) STOCK·ETF 종목을 대상으로 KIS {@code rights-by-ice}(해외주식 권리종합, 명세14)를 종목별
 * 조회한다. API 응답은 권리종합(현금배당·증자·상장폐지 등 전 권리 유형 반환)이나, <b>현금배당({@code ca_title}=현금배당) 행만</b> {@code
 * corporate_events}에 저장하고 나머지는 skip한다(RD-8 [확정: A], REQ-OVE-023/023a).
 *
 * <p><b>금액 보강(SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001)</b>: rights-by-ice는 배당 금액 필드를 반환하지 않으므로,
 * 종목별 루프 전에 {@link DividendAmountPrefetcher}가 별도 TR {@code CTRGT011R}(기간별권리조회, 명세28)을 프리페치해 {@code
 * (symbol, acpl_bass_dt) → List<금액항목>} 맵을 구성한다(REQ-ODA-010~017). 종목별 루프에서 현금배당 행을 만들 때 이 맵을 조회해
 * 리스트의 항목마다 1행(event_subtype=03→"일반배당"/75→"특별배당")을 생성한다(경로 A, 동일일자 03+75 병존 시 SUM 금지·별도 2행 보존,
 * REQ-ODA-021). 맵에 확정(dfnt_yn=Y) 매칭이 없으면 행 생성 자체를 이번 배치에서 defer한다(REQ-ODA-022/030 — Tier-1
 * INSERT-only라 나중에 채울 수 없어 확정까지 미룬다). 검증·매핑·카운팅은 {@link OverseasRightsRowAccumulator}에 위임한다.
 *
 * <p>키 선택: {@link GuardedKisExecutor} 단일 게이트를 경유한다(REQ-OVE-010). 배치 시작 시 {@link
 * KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(REQ-OVE-011)하고, 종목별 게이트 호출이 그 세션에서
 * least-busy 키를 동적 lease한다. CTRGT011R 프리페치는 단일 스레드로 VT 루프 전에 완료하므로 결과 맵은 이후 read-only로 안전 공유된다. 종목별
 * 수집은 Virtual Thread executor로 병렬 처리하며 모든 종목이 동일 세션을 공유한다(REQ-OVE-028, {@code parallelStream} 금지 —
 * commonPool 점유 회피). 서비스 코드는 별도 sleep/세마포어를 두지 않는다(REQ-OVE-014 — throttle은 게이트 위임).
 *
 * <p>매핑(REQ-OVE-061/062, REQ-ODA-021/043/046): {@code record_dt}→{@code event_date}(필수, 유니크 키 구성),
 * {@code div_lock_dt}→{@code ex_dividend_date}, {@code pay_dt}→{@code pay_date}, {@code
 * EventType.DIVIDEND} + 금액항목의 {@code rght_type_cd} 기반 라벨→{@code event_subtype}. 멱등 {@code INSERT
 * IGNORE} — 동일 {@code (stock_id, DIVIDEND, record_dt, event_subtype)}(V33, 4컬럼) 충돌 시 행 수 불변.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 해외 현금배당 수집 진입점 — 게이트 경유 키 lease·CTRGT011R 금액 프리페치 위임·종목별 병렬·현금배당 행만 매핑·멱등
// 저장·독성
// 행 흡수·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-OVERSEAS-ETC-001 REQ-OVE-010~014,020~028,061,062,
// SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001 REQ-ODA-010~062, REQ-KISGATE-001 — 게이트 경유 멀티키 진입점으로
// 다수
// 협력자(게이트·레지스트리·StockRepository·CorporateEventRepository·DividendAmountPrefetcher)가 수렴하는 fan-in 경계
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-ETC-001, SPEC-COLLECTOR-OVERSEAS-DIVIDEND-AMOUNT-001
public class OverseasRightsCollectionService {

    /** KIS 해외주식 권리종합 TR ID (명세14, 날짜 소스). */
    private static final String TR_ID = "HHDFS78330900";

    private static final String PATH = "/uapi/overseas-price/v1/quotations/rights-by-ice";

    /** 국가코드 — 미국 한정(REQ-OVE-021). */
    private static final String NATION_CODE = "US";

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final StockRepository stockRepository;
    private final CorporateEventInserter corporateEventInserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;
    private final UsMarketOpenGate usMarketOpenGate;
    private final DividendAmountPrefetcher dividendAmountPrefetcher;

    /**
     * 해외 현금배당 수집을 실행하고 집계 결과를 반환한다.
     *
     * @return 시도 종목/저장 행/skip 집계
     */
    public OverseasRightsCollectionResult collect() {
        // REQ-USMKT-014: NY 기준 오늘이 미장 휴장일 → skip
        LocalDate nyToday = LocalDate.now(NEW_YORK);
        if (!usMarketOpenGate.isOpenDay(nyToday)) {
            log.info("[overseas-rights] {} 미장 휴장일(NY 기준) → skip", nyToday);
            return emptyResult(0);
        }

        // REQ-OVE-020: 활성 미국 STOCK+ETF만 — 셀렉션은 StockRepository 계층에 캡슐화
        List<Stock> activeStocks = stockRepository.findAllActiveOverseasTradable();

        if (activeStocks.isEmpty()) {
            log.info("[overseas-rights] 수집 대상 없음 — activeStocks=0");
            return emptyResult(0);
        }

        // REQ-OVE-011: per-batch 헬스 스냅샷 1회 고정
        KeyLeaseRegistry.LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-OVE-012: 빈 스냅샷 = 전 키 사망 → per-stock 수집 0회, 전체 skip, ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[overseas-rights] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, skipped={}",
                    total,
                    total);
            return new OverseasRightsCollectionResult(total, 0, total, 0, 0, 0, 0, 0, 0, 0);
        }

        // REQ-ODA-010~017: CTRGT011R 금액 프리페치 — VT 루프 전 단일 스레드로 완료(REQ-ODA-060 전면 실패 시 빈 맵)
        Set<String> trackedSymbols = new HashSet<>();
        for (Stock stock : activeStocks) {
            trackedSymbols.add(stock.getSymbol());
        }
        DividendAmountPrefetch prefetch =
                dividendAmountPrefetcher.prefetch(session, trackedSymbols);

        // 종목별 병렬 collectStock이 공유하는 검증·매핑·카운팅 협력자(1회 collect()당 신규 인스턴스)
        OverseasRightsRowAccumulator accumulator = new OverseasRightsRowAccumulator();

        // REQ-OVE-028: Virtual Thread executor — 종목별 블로킹을 commonPool 점유 없이 처리. parallelStream 금지.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(() -> collectStock(stock, session, prefetch, accumulator));
            }
        } // close() blocks until all submitted tasks complete

        OverseasRightsCollectionResult result = accumulator.toResult(total, prefetch);
        log.info(
                "[overseas-rights] 수집 완료 — attemptedStocks={}, succeededRows={}, skippedStocks={}, "
                        + "skippedNonCashRows={}, skippedValidationRows={}, skippedToxicRows={}, "
                        + "skippedUnconfirmed={}, skippedScripDividend={}, prefetchTruncated={}, "
                        + "prefetchFailed={}",
                result.attemptedStocks(),
                result.succeededRows(),
                result.skippedStocks(),
                result.skippedNonCashRows(),
                result.skippedValidationRows(),
                result.skippedToxicRows(),
                result.skippedUnconfirmed(),
                result.skippedScripDividend(),
                result.prefetchTruncated(),
                result.prefetchFailed());
        return result;
    }

    private OverseasRightsCollectionResult emptyResult(int total) {
        return new OverseasRightsCollectionResult(total, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private void collectStock(
            Stock stock,
            KeyLeaseRegistry.LeaseSession session,
            DividendAmountPrefetch prefetch,
            OverseasRightsRowAccumulator accumulator) {

        String symbol = stock.getSymbol();
        try {
            KisOverseasRightsResponse response = fetch(session, symbol);

            // REQ-OVE-027: rt_cd=0이어도 output1 비면 종목 skip
            if (response.output1().isEmpty()) {
                accumulator.recordStockSkip();
                return;
            }

            // REQ-INSERT-011: 유효 행 누적 후 격리 삽입
            List<CorporateEvent> batch = new ArrayList<>();
            for (KisOverseasRightsResponse.RightsRow row : response.output1()) {
                accumulator.buildRow(row, stock, prefetch, batch);
            }

            if (!batch.isEmpty()) {
                AtomicInteger dbFailures = new AtomicInteger();
                corporateEventInserter.insertBatchIsolated(
                        batch,
                        (entity, ex) -> {
                            log.warn(
                                    "[overseas-rights] 독성 행 저장 실패 — skip (symbol={}, error={})",
                                    symbol,
                                    ex.getMessage());
                            accumulator.recordToxicRow();
                            dbFailures.incrementAndGet();
                        });
                accumulator.recordSucceededRows(batch.size() - dbFailures.get());
            }
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-OVE-013: retryable 재시도 소진 → graceful skip
            log.warn(
                    "[overseas-rights] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            accumulator.recordStockSkip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[overseas-rights] 인터럽트 — symbol={} skip", symbol);
            accumulator.recordStockSkip();
        } catch (NoHealthyKeyException e) {
            // 방어적: collect()에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[overseas-rights] 건강 키 0개로 skip — symbol={}", symbol);
            accumulator.recordStockSkip();
        } catch (KisTokenIssueException e) {
            // REQ-OVE-013: token 발급 실패 → graceful skip
            log.warn(
                    "[overseas-rights] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            accumulator.recordStockSkip();
        }
    }

    /** 게이트를 경유해 종목별 권리종합을 조회한다(REQ-OVE-021 — NCOD=US, ST/ED 공백=오늘±3개월). */
    private KisOverseasRightsResponse fetch(KeyLeaseRegistry.LeaseSession session, String symbol)
            throws InterruptedException {
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PATH)
                                .queryParam("NCOD", NATION_CODE)
                                .queryParam("SYMB", symbol)
                                .queryParam("ST_YMD", "")
                                .queryParam("ED_YMD", "")
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, TR_ID, KisOverseasRightsResponse.class);
    }
}
