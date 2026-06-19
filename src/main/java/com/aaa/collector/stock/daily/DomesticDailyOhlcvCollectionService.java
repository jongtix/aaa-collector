package com.aaa.collector.stock.daily;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
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
 * 국내 일봉 OHLCV 수집 서비스.
 *
 * <p>활성 관심종목({@code watchlist_removed_at IS NULL})을 대상으로 KIS {@code FHKST03010100} API에서 최근 5거래일
 * 일봉을 수집하여 {@code daily_ohlcv}에 멱등 저장한다.
 *
 * <p>키 선택: {@link GuardedKisExecutor} 단일 게이트를 경유한다. 배치 시작 시 {@link
 * KeyLeaseRegistry#openSession()}으로 per-batch 헬스 스냅샷을 1회 고정(REQ-KISGATE-006a)하고, 종목별 게이트 호출이 그 세션에서
 * least-busy 키를 동적 lease한다 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020).
 *
 * <p>모든 키 죽음(REQ-KISGATE-024, REQ-KEYDIST-020 보존): 세션 스냅샷이 비면, per-stock 수집 호출 0회 보장, 전체 skip,
 * ERROR 로그 1회 출력. 전체 키 폴백 없음.
 *
 * <p>실패 경로(REQ-BATCH-025): 특정 키의 token 발급 실패({@link KisTokenIssueException})는 해당 종목을 graceful
 * skip하고 skip 카운터에 집계한다. 영구 비즈니스 오류(인증·파라미터)는 전파한다(REQ-BATCH-024).
 *
 * <p>수집 대상: {@code asset_type IN (STOCK, ETF)} — INDEX 제외(REQ-BATCH3-024). INDEX는 U 전용 API
 * SectorIndexCollectionService가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 일봉 수집 진입점 — 게이트 경유 키 lease·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-030,-031,-032,-033,-025,-026,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001, SPEC-COLLECTOR-KISGATE-001
public class DomesticDailyOhlcvCollectionService {

    /**
     * KIS 응답의 최근 5거래일 범위를 캘린더 일수로 넉넉하게 요청할 여유 일수.
     *
     * <p>월요일 기준으로 전주 수·목·금 3거래일을 포함하려면 최소 7일 전이면 충분하나, 연휴 대비 14일로 설정한다.
     */
    private static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 극단값 가격 상한 (₩100,000,000 — 삼성바이오로직스 등 최고가 수준의 10배). */
    private static final BigDecimal PRICE_MAX = new BigDecimal("100000000");

    /** 극단값 거래량 상한 (100억 주). */
    private static final long VOLUME_MAX = 10_000_000_000L;

    private final StockRepository stockRepository;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /** 불일치 탐지 위임 — 동일 패키지 내 격리로 CouplingBetweenObjects 임계값 유지. */
    private final MismatchDetector mismatchDetector;

    /**
     * 일봉 INSERT IGNORE를 단일 커넥션 배치로 실행하고 침묵 드롭 경고를 캡처하는 경로 (REQ-OBSV-023).
     *
     * <p>JPA {@code DailyOhlcvRepository.insertIgnoreDuplicate}를 대체한다 — 동일한 INSERT IGNORE SQL이나
     * JDBC 경고 체인을 노출해 중복 외 침묵 드롭을 가시화할 수 있다.
     */
    private final WarningCountingOhlcvInserter ohlcvInserter;

    /**
     * 국내 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준일 (일반적으로 오늘 날짜)
     * @return 시도/성공/skip 종목 수 집계
     */
    public CollectionResult collect(LocalDate today) {
        // REQ-BATCH3-024: per-stock 대상은 STOCK+ETF만 — INDEX 헛호출 제거 (StockRepository 계층에서 캡슐화)
        List<Stock> activeStocks = stockRepository.findAllActiveTradable();

        if (activeStocks.isEmpty()) {
            log.info("[domestic-daily] 수집 대상 없음 — activeStocks=0");
            return new CollectionResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정 (selectHealthy 배치당 1회 호출)
        LeaseSession session = keyLeaseRegistry.openSession();

        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[domestic-daily] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new CollectionResult(total, 0, total);
        }

        LocalDate fromDate = today.minusDays(LOOKBACK_CALENDAR_DAYS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        // Virtual Thread executor — each stock runs on its own VT, blocking in limiter.consume()
        // without tying up ForkJoinPool.commonPool() threads.
        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션을 공유한다(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () -> collectStock(stock, session, fromDate, today, succeeded, skipped));
            }
        } // close() blocks until all submitted tasks complete

        return new CollectionResult(total, succeeded.get(), skipped.get());
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate fromDate,
            LocalDate toDate,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            KisDailyOhlcvResponse response = fetch(session, symbol, fromDate, toDate);
            saveValidRows(stock, symbol, response);
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip (기존 BatchResult.skip 등가 종단 동작)
            log.warn(
                    "[domestic-daily] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip (전파 아님)
            Thread.currentThread().interrupt();
            log.warn("[domestic-daily] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            // 방어적: step 2에서 단락되므로 정상 운용에서는 도달하지 않음
            log.warn("[domestic-daily] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            // REQ-BATCH-025 보존: token 발급 실패 → graceful skip
            log.warn(
                    "[domestic-daily] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private KisDailyOhlcvResponse fetch(
            LeaseSession session, String symbol, LocalDate fromDate, LocalDate toDate)
            throws InterruptedException {
        String from = fromDate.format(DATE_FMT);
        String to = toDate.format(DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", from)
                                .queryParam("FID_INPUT_DATE_2", to)
                                .queryParam("FID_PERIOD_DIV_CODE", "D")
                                .queryParam("FID_ORG_ADJ_PRC", "1")
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, "FHKST03010100", KisDailyOhlcvResponse.class);
    }

    private void saveValidRows(Stock stock, String symbol, KisDailyOhlcvResponse response) {
        List<KisDailyOhlcvResponse.DailyOhlcvRow> rows =
                response.output2().stream().filter(row -> !"Y".equals(row.modYn())).toList();

        List<KisDailyOhlcvResponse.DailyOhlcvRow> validRows =
                rows.stream().filter(row -> isValid(symbol, row)).toList();

        if (validRows.isEmpty()) {
            return;
        }

        // REQ-OHLCV2-010,-011: 불일치 탐지 위임 (N+1 방지·BigDecimal compareTo·WARN 로그·행 수정 없음).
        mismatchDetector.detectAndLog(stock.getId(), symbol, validRows, DATE_FMT);

        // REQ-OBSV-023: 한 종목의 유효 행들을 단일 커넥션 배치 INSERT IGNORE로 적재하고 침묵 드롭 경고를 캡처한다.
        // 행→파라미터 매핑은 inserter가 소유하여 본 서비스의 결합도를 낮춘다.
        ohlcvInserter.insertBatch(stock.getId(), validRows, DATE_FMT);
    }

    /**
     * 일봉 행 검증 규칙 (REQ-BATCH-033).
     *
     * <ul>
     *   <li>가격(close/open/high/low) ≤ 0 또는 극단값 초과 → invalid
     *   <li>거래량 ≤ 0 또는 극단값 초과 → invalid
     *   <li>null / 빈 문자열 필드 → invalid (NumberFormatException 처리)
     * </ul>
     */
    private boolean isValid(String symbol, KisDailyOhlcvResponse.DailyOhlcvRow row) {
        try {
            BigDecimal close = new BigDecimal(row.stckClpr());
            BigDecimal open = new BigDecimal(row.stckOprc());
            BigDecimal high = new BigDecimal(row.stckHgpr());
            BigDecimal low = new BigDecimal(row.stckLwpr());
            long volume = Long.parseLong(row.acmlVol());

            boolean invalid =
                    close.compareTo(BigDecimal.ZERO) <= 0
                            || open.compareTo(BigDecimal.ZERO) <= 0
                            || high.compareTo(BigDecimal.ZERO) <= 0
                            || low.compareTo(BigDecimal.ZERO) <= 0
                            || close.compareTo(PRICE_MAX) > 0
                            || volume <= 0
                            || volume > VOLUME_MAX;

            if (invalid) {
                log.warn(
                        "[domestic-daily] 검증 실패 (데이터 유실) — symbol={}, date={}, close={}, open={}, high={}, low={}, volume={}",
                        symbol,
                        row.stckBsopDate(),
                        row.stckClpr(),
                        row.stckOprc(),
                        row.stckHgpr(),
                        row.stckLwpr(),
                        row.acmlVol());
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            log.warn(
                    "[domestic-daily] 숫자 파싱 실패 (데이터 유실) — symbol={}, date={}, close={}, open={}, high={}, low={}, volume={}",
                    symbol,
                    row.stckBsopDate(),
                    row.stckClpr(),
                    row.stckOprc(),
                    row.stckHgpr(),
                    row.stckLwpr(),
                    row.acmlVol());
            return false;
        }
    }
}
