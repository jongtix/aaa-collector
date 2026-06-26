package com.aaa.collector.stock.fundamental;

import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.gate.GuardedKisExecutor;
import com.aaa.collector.kis.gate.KeyLeaseRegistry;
import com.aaa.collector.kis.gate.KeyLeaseRegistry.LeaseSession;
import com.aaa.collector.kis.gate.NoHealthyKeyException;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.AnalystEstimate;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * 국내주식종목투자의견 수집 서비스 (TR FHKST663300C0 → analyst_estimates, SPEC-COLLECTOR-BATCH-004).
 *
 * <p>{@link FinancialRatioCollectionService}와 동일 패턴: STOCK-only 조회→건강 키 분산→VT executor→종목당 최근 14일
 * 증분 윈도우 1회 호출→파싱·검증·매핑→멱등 저장→집계. 과거 장기 백필을 수행하지 않는다(REQ-BATCH4-014/031).
 *
 * <p>날짜 파라미터 {@code FID_INPUT_DATE_1/2}는 <b>10자 {@code 00}+YYYYMMDD 포맷</b>({@code 08} 실측 2026-06-15
 * 확정 — REQ-BATCH4-031a). 응답 {@code stck_bsop_date}는 8자 YYYYMMDD다.
 *
 * <p>검증(REQ-BATCH4-070a): 행 단위 try/catch로 파싱 실패·DECIMAL 정수부 경계 초과·BIGINT 비0 소수부를 건별 skip한다. 괴리도/괴리율
 * 음수는 정상값이므로 거부하지 않는다. institution_name 빈 문자열은 허용한다(DEFAULT '').
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 투자의견 수집 진입점 — STOCK-only 게이트 lease·14일 윈도우 호출·매핑·검증·멱등 저장·집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-013,-030~033,-070,-070a,-072,
// SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-001,-020,-024 — 게이트 경유 단일 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-004, SPEC-COLLECTOR-KISGATE-001
public class InvestOpinionCollectionService {

    /** 수집 윈도우 캘린더 일수 (확정 — BATCH-002 수급 14일 선례와 일관, REQ-BATCH4-031). */
    static final int LOOKBACK_CALENDAR_DAYS = 14;

    private static final String TR_ID = "FHKST663300C0";
    private static final String PATH = "/uapi/domestic-stock/v1/quotations/invest-opinion";

    /** 요청 날짜 포맷 = 10자 {@code 00}+YYYYMMDD ({@code 08} 실측 확정, REQ-BATCH4-031a). */
    private static final DateTimeFormatter REQUEST_DATE_FMT =
            DateTimeFormatter.ofPattern("00yyyyMMdd");

    /** 응답 {@code stck_bsop_date} 포맷 = 8자 YYYYMMDD. */
    private static final DateTimeFormatter RESPONSE_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockRepository stockRepository;
    private final AnalystEstimateRepository analystEstimateRepository;
    private final AnalystEstimateInserter analystEstimateInserter;
    private final GuardedKisExecutor guardedKisExecutor;
    private final KeyLeaseRegistry keyLeaseRegistry;

    /**
     * 투자의견 수집을 실행하고 집계 결과를 반환한다 (STOCK-only 자체 조회).
     *
     * @param today 수집 기준일
     * @return 시도/성공/skip 종목 수 집계
     */
    public FundamentalResult collect(LocalDate today) {
        List<Stock> activeStocks = stockRepository.findAllActiveStock();

        if (activeStocks.isEmpty()) {
            log.info("[invest-opinion] 수집 대상 없음 — activeStocks=0");
            return new FundamentalResult(0, 0, 0);
        }

        // REQ-KISGATE-006a: per-batch 헬스 스냅샷 1회 고정
        LeaseSession session = keyLeaseRegistry.openSession();
        int total = activeStocks.size();

        // REQ-KISGATE-024, REQ-KEYDIST-020 보존: 빈 스냅샷 = 전 키 사망 → skip-all + ERROR + no fallback
        if (session.isEmpty()) {
            log.error(
                    "[invest-opinion] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new FundamentalResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        // MI-01: 행 단위 저장/skip 집계 — VT 복수 스레드 공유이므로 AtomicInteger 사용
        AtomicInteger batchRowsSaved = new AtomicInteger();
        AtomicInteger batchRowsSkipped = new AtomicInteger();

        // 키 선택은 게이트가 세션 스냅샷에서 동적 lease한다(REQ-KISGATE-020). 모든 종목이 동일 세션 공유(REQ-KISGATE-031).
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Stock stock : activeStocks) {
                executor.submit(
                        () ->
                                collectStock(
                                        stock,
                                        session,
                                        today,
                                        windowStart,
                                        succeeded,
                                        skipped,
                                        batchRowsSaved,
                                        batchRowsSkipped));
            }
        }

        FundamentalResult result = new FundamentalResult(total, succeeded.get(), skipped.get());
        log.info(
                "[invest-opinion] 수집 완료 — attempted={}, succeeded={}, skipped={}, totalRowsSaved={}, totalRowsSkipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped(),
                batchRowsSaved.get(),
                batchRowsSkipped.get());
        return result;
    }

    private void collectStock(
            Stock stock,
            LeaseSession session,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped,
            AtomicInteger batchRowsSaved,
            AtomicInteger batchRowsSkipped) {

        String symbol = stock.getSymbol();
        try {
            KisInvestOpinionResponse response = fetch(session, symbol, windowStart, today);
            // MI-01: per-stock 행 단위 결과 집계 (saveValidRows 내부는 순차 실행)
            int[] rowCounts = saveValidRows(stock, symbol, response);
            int rowsSaved = rowCounts[0];
            int rowsSkipped = rowCounts[1];
            batchRowsSaved.addAndGet(rowsSaved);
            batchRowsSkipped.addAndGet(rowsSkipped);
            log.info(
                    "[invest-opinion] stock 완료 — symbol={}, rowsSaved={}, rowsSkipped={}",
                    symbol,
                    rowsSaved,
                    rowsSkipped);
            succeeded.incrementAndGet();

        } catch (KisRateLimitException | RestClientException e) {
            // REQ-KISGATE-022: retryable 재시도 소진 → graceful skip
            log.warn(
                    "[invest-opinion] skip (재시도 소진) — symbol={}, reason={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        } catch (InterruptedException e) {
            // RETRY-001 REQ-RETRY-017 보존: 인터럽트 플래그 복원 후 skip
            Thread.currentThread().interrupt();
            log.warn("[invest-opinion] 인터럽트 — symbol={} skip", symbol);
            skipped.incrementAndGet();
        } catch (NoHealthyKeyException e) {
            log.warn("[invest-opinion] 건강 키 0개로 skip — symbol={}", symbol);
            skipped.incrementAndGet();
        } catch (KisTokenIssueException e) {
            log.warn(
                    "[invest-opinion] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private KisInvestOpinionResponse fetch(
            LeaseSession session, String symbol, LocalDate windowStart, LocalDate today)
            throws InterruptedException {
        String from = windowStart.format(REQUEST_DATE_FMT);
        String to = today.format(REQUEST_DATE_FMT);
        Function<UriBuilder, URI> uriCustomizer =
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_COND_SCR_DIV_CODE", "16633")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", from)
                                .queryParam("FID_INPUT_DATE_2", to)
                                .build();
        return guardedKisExecutor.execute(
                session, uriCustomizer, TR_ID, KisInvestOpinionResponse.class);
    }

    /**
     * 응답 행을 순차 처리하여 유효 행을 저장하고 결과를 반환한다.
     *
     * @return {@code int[]{rowsSaved, rowsSkipped}} — per-stock 로그 및 배치 합산에 사용 (MI-01)
     */
    private int[] saveValidRows(Stock stock, String symbol, KisInvestOpinionResponse response) {
        // REQ-INSERT-009: 유효 행 누적 후 단일 배치 INSERT IGNORE (소용량)
        List<AnalystEstimate> batch = new ArrayList<>();
        int rowsSkipped = 0;
        for (KisInvestOpinionResponse.InvestOpinionRow row : response.output()) {
            Optional<AnalystEstimate> entity = buildIfValid(stock, symbol, row);
            if (entity.isPresent()) {
                batch.add(entity.get());
            } else {
                rowsSkipped++;
            }
        }
        if (!batch.isEmpty()) {
            analystEstimateInserter.insertBatch(batch);
        }
        return new int[] {batch.size(), rowsSkipped};
    }

    /**
     * 단일 행 파싱·검증 후 엔티티를 반환한다.
     *
     * <p>REQ-INSERT-009: 누적 배치 방식으로 전환 — 여기서는 엔티티만 빌드하고 저장하지 않는다.
     *
     * @return 엔티티(성공) 또는 empty(검증 실패 skip)
     */
    private Optional<AnalystEstimate> buildIfValid(
            Stock stock, String symbol, KisInvestOpinionResponse.InvestOpinionRow row) {

        if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
            log.warn("[invest-opinion] 검증 실패 (trade_date null) — symbol={}", symbol);
            return Optional.empty();
        }

        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(row.stckBsopDate().trim(), RESPONSE_DATE_FMT);
        } catch (DateTimeParseException e) {
            log.warn(
                    "[invest-opinion] 검증 실패 (trade_date 파싱 불가) — symbol={}, date={}",
                    symbol,
                    row.stckBsopDate());
            return Optional.empty();
        }

        try {
            return Optional.of(
                    AnalystEstimate.builder()
                            .stock(stock)
                            .tradeDate(tradeDate)
                            // institution_name 빈 문자열 허용 (DEFAULT '' — uk 구성요소)
                            .institutionName(row.mbcrName() == null ? "" : row.mbcrName())
                            .opinion(row.invtOpnn())
                            .opinionCode(row.invtOpnnClsCode())
                            .prevOpinion(row.rgbfInvtOpnn())
                            .prevOpinionCode(row.rgbfInvtOpnnClsCode())
                            .targetPrice(FundamentalValueParser.parseBigInt(row.htsGoalPrc()))
                            .prevClose(FundamentalValueParser.parseBigInt(row.stckPrdyClpr()))
                            .gapNDay(FundamentalValueParser.parseDecimal(row.stckNdayEsdg()))
                            .gapRateNDay(FundamentalValueParser.parseDecimal(row.ndayDprt()))
                            .gapFutures(FundamentalValueParser.parseDecimal(row.stftEsdg()))
                            .gapRateFutures(FundamentalValueParser.parseDecimal(row.dprt()))
                            .build());
        } catch (NumberFormatException | ArithmeticException e) {
            // 파싱 실패·DECIMAL 정수부 경계 초과·BIGINT 비0 소수부·long 범위 초과 → 건별 skip (REQ-BATCH4-070a)
            log.warn(
                    "[invest-opinion] 검증 실패 (건별 skip) — symbol={}, date={}, reason={}",
                    symbol,
                    row.stckBsopDate(),
                    e.getMessage());
            return Optional.empty();
        }
    }
}
