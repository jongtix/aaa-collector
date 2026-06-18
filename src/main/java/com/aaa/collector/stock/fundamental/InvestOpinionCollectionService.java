package com.aaa.collector.stock.fundamental;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.AnalystEstimate;
import com.aaa.collector.stock.AnalystEstimateRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
// @MX:ANCHOR: [AUTO] 국내 투자의견 수집 진입점 — STOCK-only 분산·14일 윈도우 호출·매핑·검증·멱등 저장·집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-010,-011,-013,-030~033,-070,-070a,-072 — 스케줄러에서
// 호출하는 단일 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-004
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
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

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

        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);
        int total = activeStocks.size();

        if (allocation.isEmpty()) {
            log.error(
                    "[invest-opinion] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new FundamentalResult(total, 0, total);
        }

        LocalDate windowStart = today.minusDays(LOOKBACK_CALENDAR_DAYS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            allocation.forEach(
                    (credential, stocks) -> {
                        for (Stock stock : stocks) {
                            executor.submit(
                                    () ->
                                            collectStock(
                                                    stock,
                                                    credential,
                                                    today,
                                                    windowStart,
                                                    succeeded,
                                                    skipped));
                        }
                    });
        }

        FundamentalResult result = new FundamentalResult(total, succeeded.get(), skipped.get());
        log.info(
                "[invest-opinion] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped());
        return result;
    }

    private void collectStock(
            Stock stock,
            KisAccountCredential credential,
            LocalDate today,
            LocalDate windowStart,
            AtomicInteger succeeded,
            AtomicInteger skipped) {

        String symbol = stock.getSymbol();
        try {
            BatchResult<KisInvestOpinionResponse> batchResult =
                    fetch(credential, symbol, windowStart, today);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn("[invest-opinion] skip (데이터 유실) — symbol={}, reason={}", symbol, reason);
                skipped.incrementAndGet();
                return;
            }

            KisInvestOpinionResponse response = batchResult.getValue().orElseThrow();
            saveValidRows(stock, symbol, response);
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            log.warn(
                    "[invest-opinion] 토큰 발급 실패로 skip — symbol={}, error={}",
                    symbol,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisInvestOpinionResponse> fetch(
            KisAccountCredential credential,
            String symbol,
            LocalDate windowStart,
            LocalDate today) {
        String from = windowStart.format(REQUEST_DATE_FMT);
        String to = today.format(REQUEST_DATE_FMT);
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path(PATH)
                                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                                .queryParam("FID_COND_SCR_DIV_CODE", "16633")
                                .queryParam("FID_INPUT_ISCD", symbol)
                                .queryParam("FID_INPUT_DATE_1", from)
                                .queryParam("FID_INPUT_DATE_2", to)
                                .build(),
                TR_ID,
                KisInvestOpinionResponse.class,
                symbol);
    }

    private void saveValidRows(Stock stock, String symbol, KisInvestOpinionResponse response) {
        for (KisInvestOpinionResponse.InvestOpinionRow row : response.output()) {
            insertIfValid(stock, symbol, row);
        }
    }

    private void insertIfValid(
            Stock stock, String symbol, KisInvestOpinionResponse.InvestOpinionRow row) {

        if (row.stckBsopDate() == null || row.stckBsopDate().isBlank()) {
            log.warn("[invest-opinion] 검증 실패 (trade_date null) — symbol={}", symbol);
            return;
        }

        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(row.stckBsopDate().trim(), RESPONSE_DATE_FMT);
        } catch (DateTimeParseException e) {
            log.warn(
                    "[invest-opinion] 검증 실패 (trade_date 파싱 불가) — symbol={}, date={}",
                    symbol,
                    row.stckBsopDate());
            return;
        }

        try {
            AnalystEstimate entity =
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
                            .build();
            analystEstimateRepository.insertIgnoreDuplicate(entity);
        } catch (NumberFormatException | ArithmeticException e) {
            // 파싱 실패·DECIMAL 정수부 경계 초과·BIGINT 비0 소수부·long 범위 초과 → 건별 skip (REQ-BATCH4-070a)
            log.warn(
                    "[invest-opinion] 검증 실패 (건별 skip) — symbol={}, date={}, reason={}",
                    symbol,
                    row.stckBsopDate(),
                    e.getMessage());
        }
    }
}
