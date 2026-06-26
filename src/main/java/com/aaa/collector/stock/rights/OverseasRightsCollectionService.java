package com.aaa.collector.stock.rights;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.CorporateEvent;
import com.aaa.collector.stock.CorporateEventInserter;
import com.aaa.collector.stock.CorporateEventRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.EventType;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/**
 * 해외 현금배당 수집 서비스 (TR HHDFS78330900, SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>활성 미국(NYSE/NASDAQ/AMEX) STOCK·ETF 종목을 대상으로 KIS {@code rights-by-ice}(해외주식 권리종합, 명세14)를 종목별
 * 조회한다. API 응답은 권리종합(현금배당·증자·상폐 등 전 권리 유형 반환)이나, <b>현금배당({@code ca_title}=현금배당) 행만</b> {@code
 * corporate_events}에 저장하고 나머지는 skip한다(RD-8 [확정: A], REQ-OVE-023/023a).
 *
 * <p>키 선택: {@link GuardedKisExecutor} 단일 게이트를 경유한다(REQ-OVE-010). 배치 시작 시 {@link
 * KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(REQ-OVE-011)하고, 종목별 게이트 호출이 그 세션에서
 * least-busy 키를 동적 lease한다. 종목별 수집은 Virtual Thread executor로 병렬 처리하며 모든 종목이 동일 세션을
 * 공유한다(REQ-OVE-028, {@code parallelStream} 금지 — commonPool 점유 회피). 서비스 코드는 별도 sleep/세마포어를 두지
 * 않는다(REQ-OVE-014 — throttle은 게이트 위임).
 *
 * <p>매핑(REQ-OVE-061/062): {@code record_dt}→{@code event_date}(필수, 유니크 키 구성), {@code
 * div_lock_dt}→{@code ex_dividend_date}, {@code pay_dt}→{@code pay_date}, {@code
 * EventType.DIVIDEND} + 원본 {@code ca_title}→{@code event_subtype}. 멱등 {@code INSERT IGNORE} — 동일
 * {@code (stock_id, DIVIDEND, record_dt)} 충돌 시 행 수 불변.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 해외 현금배당 수집 진입점 — 게이트 경유 키 lease·종목별 병렬·현금배당 행만 매핑·멱등 저장·독성 행 흡수·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-OVERSEAS-ETC-001 REQ-OVE-010~014,020~028,061,062, REQ-KISGATE-001 —
// 게이트 경유 멀티키 진입점으로 다수 협력자(게이트·레지스트리·StockRepository·CorporateEventRepository)가 수렴하는 fan-in 경계
// @MX:SPEC: SPEC-COLLECTOR-OVERSEAS-ETC-001
public class OverseasRightsCollectionService {

    /** KIS 해외주식 권리종합 TR ID (명세14). */
    private static final String TR_ID = "HHDFS78330900";

    private static final String PATH = "/uapi/overseas-price/v1/quotations/rights-by-ice";

    /** 국가코드 — 미국 한정(REQ-OVE-021). */
    private static final String NATION_CODE = "US";

    /** 권리유형 현금배당 판정 값(REQ-OVE-023, RD-2). */
    private static final String CASH_DIVIDEND_TITLE = "현금배당";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockRepository stockRepository;
    private final CorporateEventRepository corporateEventRepository;
    private final CorporateEventInserter corporateEventInserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 해외 현금배당 수집을 실행하고 집계 결과를 반환한다.
     *
     * @return 시도 종목/저장 행/skip 집계
     */
    public OverseasRightsCollectionResult collect() {
        // REQ-OVE-020: 활성 미국 STOCK+ETF만 — 셀렉션은 StockRepository 계층에 캡슐화
        List<Stock> activeStocks = stockRepository.findAllActiveOverseasTradable();

        if (activeStocks.isEmpty()) {
            log.info("[overseas-rights] 수집 대상 없음 — activeStocks=0");
            return new OverseasRightsCollectionResult(0, 0, 0, 0, 0, 0);
        }

        // REQ-OVE-011: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-OVE-012: 빈 스냅샷 = 전 키 사망 → per-stock 수집 0회, 전체 skip, ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[overseas-rights] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, skipped={}",
                    total,
                    total);
            return new OverseasRightsCollectionResult(total, 0, total, 0, 0, 0);
        }

        AtomicInteger succeededRows = new AtomicInteger();
        AtomicInteger skippedStocks = new AtomicInteger();
        AtomicInteger skippedNonCashRows = new AtomicInteger();
        AtomicInteger skippedValidationRows = new AtomicInteger();
        // W1: DB 독성 행(DataAccessException 흡수)을 검증 skip과 분리 집계 — 42,460-failure 사건군 관측성 보존
        AtomicInteger skippedToxicRows = new AtomicInteger();

        // REQ-OVE-028: Virtual Thread executor — 종목별 블로킹을 commonPool 점유 없이 처리. parallelStream 금지.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () ->
                                collectStock(
                                        stock,
                                        session,
                                        succeededRows,
                                        skippedStocks,
                                        skippedNonCashRows,
                                        skippedValidationRows,
                                        skippedToxicRows));
            }
        } // close() blocks until all submitted tasks complete

        OverseasRightsCollectionResult result =
                new OverseasRightsCollectionResult(
                        total,
                        succeededRows.get(),
                        skippedStocks.get(),
                        skippedNonCashRows.get(),
                        skippedValidationRows.get(),
                        skippedToxicRows.get());
        log.info(
                "[overseas-rights] 수집 완료 — attemptedStocks={}, succeededRows={}, skippedStocks={}, skippedNonCashRows={}, skippedValidationRows={}, skippedToxicRows={}",
                result.attemptedStocks(),
                result.succeededRows(),
                result.skippedStocks(),
                result.skippedNonCashRows(),
                result.skippedValidationRows(),
                result.skippedToxicRows());
        return result;
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            AtomicInteger succeededRows,
            AtomicInteger skippedStocks,
            AtomicInteger skippedNonCashRows,
            AtomicInteger skippedValidationRows,
            AtomicInteger skippedToxicRows) {

        String symbol = stock.getSymbol();
        try {
            KisOverseasRightsResponse response = fetch(session, symbol);

            // REQ-OVE-027: rt_cd=0이어도 output1 비면 종목 skip
            if (response.output1().isEmpty()) {
                skippedStocks.incrementAndGet();
                return;
            }

            for (KisOverseasRightsResponse.RightsRow row : response.output1()) {
                processRow(
                        row,
                        stock,
                        symbol,
                        succeededRows,
                        skippedNonCashRows,
                        skippedValidationRows,
                        skippedToxicRows);
            }
        } catch (KisRateLimitException | RestClientException e) {
            // REQ-OVE-013: retryable 재시도 소진 → graceful skip
            log.warn(
                    "[overseas-rights] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skippedStocks.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[overseas-rights] 인터럽트 — symbol={} skip", symbol);
            skippedStocks.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            // 방어적: collect()에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[overseas-rights] 건강 키 0개로 skip — symbol={}", symbol);
            skippedStocks.incrementAndGet();
        } catch (KisTokenIssueException e) {
            // REQ-OVE-013: token 발급 실패 → graceful skip
            log.warn(
                    "[overseas-rights] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skippedStocks.incrementAndGet();
        }
    }

    /**
     * 권리 1건을 처리한다 — 비현금배당 skip(REQ-OVE-023a), 현금배당 매핑·검증·멱등 저장.
     *
     * <p>독성 행 흡수: {@code insertIgnoreDuplicate} 예외가 종목 내 다음 행 처리를 차단하지 않도록 예외를 흡수하고 현재 행만
     * skip한다(REQ-OVE-026, W1).
     */
    // @MX:WARN: [AUTO] 독성 행 예외 흡수 — 영구 정체 방지 (W1)
    // @MX:REASON: insertIgnoreDuplicate 예외가 종목 내 다음 행 처리·배치 전체 수집을 차단하지 않도록 현재 행만
    // skip한다(REQ-OVE-026).
    private void processRow(
            KisOverseasRightsResponse.RightsRow row,
            Stock stock,
            String symbol,
            AtomicInteger succeededRows,
            AtomicInteger skippedNonCashRows,
            AtomicInteger skippedValidationRows,
            AtomicInteger skippedToxicRows) {
        // REQ-OVE-023a: 비현금배당(증자·상폐 등) 행은 저장하지 않고 skip
        if (!CASH_DIVIDEND_TITLE.equals(row.caTitle())) {
            skippedNonCashRows.incrementAndGet();
            return;
        }

        CorporateEvent entity = mapToEntity(row, stock);
        if (entity == null) {
            skippedValidationRows.incrementAndGet();
            return;
        }

        // REQ-OVE-024/061: uk_corporate_events 멱등 저장
        try {
            corporateEventInserter.insertBatch(List.of(entity));
            succeededRows.incrementAndGet();
        } catch (DataAccessException ex) {
            // W1: DB 독성 행 — 검증 skip과 별도 카운터로 집계해 침묵 드롭 관측성 보존(42,460-failure 사건군)
            log.warn(
                    "[overseas-rights] 독성 행 저장 실패 — skip (symbol={}, error={}: {})",
                    symbol,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            skippedToxicRows.incrementAndGet();
        }
    }

    /** 게이트를 경유해 종목별 권리종합을 조회한다(REQ-OVE-021 — NCOD=US, ST/ED 공백=오늘±3개월). */
    private KisOverseasRightsResponse fetch(LeaseSession session, String symbol)
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

    /**
     * 현금배당 RightsRow → CorporateEvent 매핑(REQ-OVE-061/062/070).
     *
     * <p>{@code record_dt}는 필수(없으면 skip). {@code div_lock_dt}→{@code ex_dividend_date}, {@code
     * pay_dt}→{@code pay_date}는 선택(파싱 실패 시 null). 금액 컬럼은 {@code rights_by_ice} 미반환이라 매핑하지 않는다(해외 행
     * NULL).
     *
     * @return null 이면 skip(필수 날짜 누락·파싱 실패)
     */
    @SuppressWarnings("PMD.GuardLogStatement") // debug 파라미터 구성 비용은 무시 가능
    private CorporateEvent mapToEntity(KisOverseasRightsResponse.RightsRow row, Stock stock) {
        // REQ-OVE-025/070: 필수 기준일(record_dt) null/공백/파싱 실패 → skip
        LocalDate eventDate = parseDateOrNull(row.recordDt());
        if (eventDate == null) {
            log.debug(
                    "[overseas-rights] 검증 실패 (record_dt={}) — symbol={}",
                    row.recordDt(),
                    stock.getSymbol());
            return null;
        }

        LocalDate exDividendDate = parseDateOrNull(row.divLockDt());
        LocalDate payDate = parseDateOrNull(row.payDt());

        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .exDividendDate(exDividendDate)
                .eventSubtype(row.caTitle())
                .payDate(payDate)
                .build();
    }

    /** yyyyMMdd 날짜 파싱 — null/공백/형식 오류 시 null 반환. */
    private LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim(), DATE_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
