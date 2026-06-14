package com.aaa.collector.stock.daily;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내 일봉 OHLCV 수집 서비스.
 *
 * <p>활성 관심종목({@code watchlist_removed_at IS NULL})을 대상으로 KIS {@code FHKST03010100} API에서 최근 5거래일
 * 일봉을 수집하여 {@code daily_ohlcv}에 멱등 저장한다.
 *
 * <p>키 분산: {@link HealthyKeyRoundRobinDistributor}가 건강 키 집합을 산출하고, 결정적 라운드로빈으로 종목→키를 할당한다
 * (SPEC-COLLECTOR-KEYDIST-001 REQ-KEYDIST-001,-002,-003,-010).
 *
 * <p>모든 키 죽음(REQ-KEYDIST-020, D3): distributor 반환이 빈 할당이면, per-stock 수집 호출 0회 보장, 전체 skip, ERROR 로그
 * 1회 출력. 전체 키 폴백 없음.
 *
 * <p>실패 경로(REQ-BATCH-025): 특정 키의 token 발급 실패({@link KisTokenIssueException})는 해당 종목을 graceful
 * skip하고 skip 카운터에 집계한다. 영구 비즈니스 오류(인증·파라미터)는 전파한다(REQ-BATCH-024).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 일봉 수집 진입점 — 건강 키 분산·검증·멱등 저장·skip 집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-001 REQ-BATCH-030,-031,-032,-033,-025,-026,
// SPEC-COLLECTOR-KEYDIST-001 REQ-KEYDIST-010,-020
// @MX:SPEC: SPEC-COLLECTOR-BATCH-001, SPEC-COLLECTOR-KEYDIST-001
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
    private final DailyOhlcvRepository dailyOhlcvRepository;
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

    /** 불일치 탐지 위임 — 동일 패키지 내 격리로 CouplingBetweenObjects 임계값 유지. */
    private final MismatchDetector mismatchDetector;

    /**
     * 국내 일봉 수집을 실행하고 집계 결과를 반환한다.
     *
     * @param today 수집 기준일 (일반적으로 오늘 날짜)
     * @return 시도/성공/skip 종목 수 집계
     */
    public CollectionResult collect(LocalDate today) {
        List<Stock> activeStocks = stockRepository.findAllActive();

        if (activeStocks.isEmpty()) {
            log.info("[domestic-daily] 수집 대상 없음 — activeStocks=0");
            return new CollectionResult(0, 0, 0);
        }

        // REQ-KEYDIST-010: delegate stock→key assignment to distributor (healthy-key round-robin)
        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);

        int total = activeStocks.size();

        // REQ-KEYDIST-020, D3: empty allocation = all keys dead → skip-all + ERROR + no fallback
        if (allocation.isEmpty()) {
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
        // Key assignment is determined by the distributor (healthy-key round-robin).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            allocation.forEach(
                    (credential, stocks) -> {
                        for (Stock stock : stocks) {
                            executor.submit(
                                    () ->
                                            collectStock(
                                                    stock,
                                                    credential,
                                                    fromDate,
                                                    today,
                                                    succeeded,
                                                    skipped));
                        }
                    });
        } // close() blocks until all submitted tasks complete

        return new CollectionResult(total, succeeded.get(), skipped.get());
    }

    private void collectStock(
            Stock stock,
            KisAccountCredential credential,
            LocalDate fromDate,
            LocalDate toDate,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            BatchResult<KisDailyOhlcvResponse> batchResult =
                    fetchBatch(credential, symbol, fromDate, toDate);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn("[domestic-daily] skip (데이터 유실) — symbol={}, reason={}", symbol, reason);
                skipped.incrementAndGet();
                return;
            }

            KisDailyOhlcvResponse response = batchResult.getValue().orElseThrow();
            saveValidRows(stock, symbol, response);
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            // REQ-BATCH-025: token 발급 실패 → graceful skip
            log.warn(
                    "[domestic-daily] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisDailyOhlcvResponse> fetchBatch(
            KisAccountCredential credential, String symbol, LocalDate fromDate, LocalDate toDate) {
        String from = fromDate.format(DATE_FMT);
        String to = toDate.format(DATE_FMT);
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", from)
                                .queryParam("FID_INPUT_DATE_2", to)
                                .queryParam("FID_PERIOD_DIV_CODE", "D")
                                .queryParam("FID_ORG_ADJ_PRC", "1")
                                .build(),
                "FHKST03010100",
                KisDailyOhlcvResponse.class,
                symbol);
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

        for (KisDailyOhlcvResponse.DailyOhlcvRow row : validRows) {
            LocalDate tradeDate = LocalDate.parse(row.stckBsopDate(), DATE_FMT);
            insertRow(stock, row, tradeDate);
        }
    }

    private void insertRow(
            Stock stock, KisDailyOhlcvResponse.DailyOhlcvRow row, LocalDate tradeDate) {
        BigDecimal open = new BigDecimal(row.stckOprc());
        BigDecimal high = new BigDecimal(row.stckHgpr());
        BigDecimal low = new BigDecimal(row.stckLwpr());
        BigDecimal close = new BigDecimal(row.stckClpr());
        long volume = Long.parseLong(row.acmlVol());
        long tradingValue = Long.parseLong(row.acmlTrPbmn());

        dailyOhlcvRepository.insertIgnoreDuplicate(
                stock.getId(), tradeDate, open, high, low, close, volume, tradingValue);
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
