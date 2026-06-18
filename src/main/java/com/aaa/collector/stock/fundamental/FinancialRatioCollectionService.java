package com.aaa.collector.stock.fundamental;

import com.aaa.collector.kis.batch.BatchRestExecutor;
import com.aaa.collector.kis.batch.BatchResult;
import com.aaa.collector.kis.batch.HealthyKeyRoundRobinDistributor;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenIssueException;
import com.aaa.collector.stock.Financial;
import com.aaa.collector.stock.FinancialRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.PeriodType;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 국내주식재무비율 수집 서비스 (TR FHKST66430300 → financials, SPEC-COLLECTOR-BATCH-004).
 *
 * <p>{@link com.aaa.collector.stock.daily.DomesticDailyOhlcvCollectionService}·{@link
 * com.aaa.collector.stock.supply.InvestorTrendCollectionService} 패턴 답습: STOCK-only 조회→건강 키 분산→VT
 * executor→종목당 연간(0)+분기(1) 2회 호출→파싱·검증·매핑→멱등 저장→집계.
 *
 * <p>대상은 {@code asset_type = STOCK} 한정(REQ-BATCH4-013). 멀티키 라운드로빈 경로만 사용하며 단일키 경로를 사용하지 않는다
 * (REQ-BATCH4-010). 모든 키 죽음 시 호출 0회·전체 skip·ERROR 1회(REQ-BATCH4-011). 완료 이벤트를 발행하지 않고 자기 완료 로깅만
 * 한다(REQ-BATCH4-012).
 *
 * <p>검증(REQ-BATCH4-070a): 행 단위 try/catch로 파싱 실패·DECIMAL 정수부 경계 초과·BIGINT 비0 소수부를 건별 skip한다. 증가율 음수는
 * 정상값이므로 거부하지 않는다. eps/sps/bps는 {@code new BigDecimal}→{@code longValueExact()}로 무손실 정수 변환한다({@code
 * "6993.00"}→6993).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 국내 재무비율 수집 진입점 — STOCK-only 분산·연간+분기 2회 호출·매핑·검증·멱등 저장·집계 담당
// @MX:REASON: SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-010,-011,-013,-020~024,-070,-070a,-072 — 스케줄러에서
// 호출하는 단일 진입점
// @MX:SPEC: SPEC-COLLECTOR-BATCH-004
public class FinancialRatioCollectionService {

    private static final String TR_ID = "FHKST66430300";
    private static final String PATH = "/uapi/domestic-stock/v1/finance/financial-ratio";

    /** 분류구분: 0=년(ANNUAL), 1=분기(QUARTERLY) — {@code 06} 명세 확정. */
    private static final String DIV_ANNUAL = "0";

    private static final String DIV_QUARTERLY = "1";

    /** {@code stac_yymm} 포맷 길이 (YYYYMM 6자 — {@code 06} 실측 확정). */
    private static final int STAC_YYMM_LENGTH = 6;

    private final StockRepository stockRepository;
    private final FinancialRepository financialRepository;
    private final BatchRestExecutor batchRestExecutor;
    private final HealthyKeyRoundRobinDistributor distributor;

    /**
     * 재무비율 수집을 실행하고 집계 결과를 반환한다 (STOCK-only 자체 조회).
     *
     * @return 시도/성공/skip (종목×분류구분) 단위 집계
     */
    public FundamentalResult collect() {
        List<Stock> activeStocks = stockRepository.findAllActiveStock();

        if (activeStocks.isEmpty()) {
            log.info("[financial-ratio] 수집 대상 없음 — activeStocks=0");
            return new FundamentalResult(0, 0, 0);
        }

        Map<KisAccountCredential, List<Stock>> allocation = distributor.distribute(activeStocks);
        // 종목당 연간+분기 2회 호출 → attempted는 종목 수의 2배
        int total = activeStocks.size() * 2;

        if (allocation.isEmpty()) {
            log.error(
                    "[financial-ratio] 모든 키 죽음 — per-stock 수집 0회, 전체 skip. attempted={}, succeeded=0, skipped={}",
                    total,
                    total);
            return new FundamentalResult(total, 0, total);
        }

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        // MI-01: 행 단위 저장/skip 집계 — VT 복수 스레드 공유이므로 AtomicInteger 사용
        AtomicInteger batchRowsSaved = new AtomicInteger();
        AtomicInteger batchRowsSkipped = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            allocation.forEach(
                    (credential, stocks) -> {
                        for (Stock stock : stocks) {
                            executor.submit(
                                    () ->
                                            collectStock(
                                                    stock,
                                                    credential,
                                                    succeeded,
                                                    skipped,
                                                    batchRowsSaved,
                                                    batchRowsSkipped));
                        }
                    });
        }

        FundamentalResult result = new FundamentalResult(total, succeeded.get(), skipped.get());
        log.info(
                "[financial-ratio] 수집 완료 — attempted={}, succeeded={}, skipped={}, totalRowsSaved={}, totalRowsSkipped={}",
                result.attempted(),
                result.succeeded(),
                result.skipped(),
                batchRowsSaved.get(),
                batchRowsSkipped.get());
        return result;
    }

    private void collectStock(
            Stock stock,
            KisAccountCredential credential,
            AtomicInteger succeeded,
            AtomicInteger skipped,
            AtomicInteger batchRowsSaved,
            AtomicInteger batchRowsSkipped) {
        collectDivision(
                stock,
                credential,
                DIV_ANNUAL,
                PeriodType.ANNUAL,
                succeeded,
                skipped,
                batchRowsSaved,
                batchRowsSkipped);
        collectDivision(
                stock,
                credential,
                DIV_QUARTERLY,
                PeriodType.QUARTERLY,
                succeeded,
                skipped,
                batchRowsSaved,
                batchRowsSkipped);
    }

    private void collectDivision(
            Stock stock,
            KisAccountCredential credential,
            String divClsCode,
            PeriodType periodType,
            AtomicInteger succeeded,
            AtomicInteger skipped,
            AtomicInteger batchRowsSaved,
            AtomicInteger batchRowsSkipped) {

        String symbol = stock.getSymbol();
        try {
            BatchResult<KisFinancialRatioResponse> batchResult =
                    fetch(credential, symbol, divClsCode);

            if (!batchResult.isSuccess()) {
                String reason = batchResult.getSkipReason().orElse("알 수 없음");
                log.warn(
                        "[financial-ratio] skip (데이터 유실) — symbol={}, periodType={}, reason={}",
                        symbol,
                        periodType,
                        reason);
                skipped.incrementAndGet();
                return;
            }

            KisFinancialRatioResponse response = batchResult.getValue().orElseThrow();
            // MI-01: division별 행 단위 결과를 지역 카운터로 집계 (saveValidRows 내부는 순차 실행)
            int[] rowCounts = saveValidRows(stock, symbol, periodType, response);
            int rowsSaved = rowCounts[0];
            int rowsSkipped = rowCounts[1];
            batchRowsSaved.addAndGet(rowsSaved);
            batchRowsSkipped.addAndGet(rowsSkipped);
            log.info(
                    "[financial-ratio] div 완료 — symbol={}, div={}, rowsSaved={}, rowsSkipped={}",
                    symbol,
                    periodType,
                    rowsSaved,
                    rowsSkipped);
            succeeded.incrementAndGet();

        } catch (KisTokenIssueException e) {
            log.warn(
                    "[financial-ratio] 토큰 발급 실패로 skip — symbol={}, periodType={}, error={}",
                    symbol,
                    periodType,
                    e.getMessage());
            skipped.incrementAndGet();
        }
    }

    private BatchResult<KisFinancialRatioResponse> fetch(
            KisAccountCredential credential, String symbol, String divClsCode) {
        return batchRestExecutor.execute(
                credential,
                uri ->
                        uri.path(PATH)
                                .queryParam("fid_cond_mrkt_div_code", "J")
                                .queryParam("fid_input_iscd", symbol)
                                .queryParam("FID_DIV_CLS_CODE", divClsCode)
                                .build(),
                TR_ID,
                KisFinancialRatioResponse.class,
                symbol);
    }

    /**
     * 응답 행을 순차 처리하여 유효 행을 저장하고 결과를 반환한다.
     *
     * @return {@code int[]{rowsSaved, rowsSkipped}} — division별 로그 및 배치 합산에 사용 (MI-01)
     */
    private int[] saveValidRows(
            Stock stock, String symbol, PeriodType periodType, KisFinancialRatioResponse response) {
        int rowsSaved = 0;
        int rowsSkipped = 0;
        for (KisFinancialRatioResponse.FinancialRatioRow row : response.output()) {
            boolean saved = insertIfValid(stock, symbol, periodType, row);
            if (saved) {
                rowsSaved++;
            } else {
                rowsSkipped++;
            }
        }
        return new int[] {rowsSaved, rowsSkipped};
    }

    /**
     * 단일 행 파싱·검증·저장을 수행한다.
     *
     * @return 저장 성공 여부 ({@code false} = 검증 실패 skip)
     */
    private boolean insertIfValid(
            Stock stock,
            String symbol,
            PeriodType periodType,
            KisFinancialRatioResponse.FinancialRatioRow row) {

        if (row.stacYymm() == null || row.stacYymm().isBlank()) {
            log.warn(
                    "[financial-ratio] 검증 실패 (stac_yymm null) — symbol={}, periodType={}",
                    symbol,
                    periodType);
            return false;
        }

        LocalDate periodDate = parsePeriodDate(row.stacYymm());
        if (periodDate == null) {
            log.warn(
                    "[financial-ratio] 검증 실패 (stac_yymm 파싱 불가) — symbol={}, periodType={}, stacYymm={}",
                    symbol,
                    periodType,
                    row.stacYymm());
            return false;
        }

        try {
            Financial entity =
                    Financial.builder()
                            .stock(stock)
                            .periodType(periodType)
                            .periodDate(periodDate)
                            .revenueGrowth(FundamentalValueParser.parseDecimal(row.grs()))
                            .operatingProfitGrowth(
                                    FundamentalValueParser.parseDecimal(row.bsopPrfiInrt()))
                            .netIncomeGrowth(FundamentalValueParser.parseDecimal(row.ntinInrt()))
                            .roe(FundamentalValueParser.parseDecimal(row.roeVal()))
                            .eps(FundamentalValueParser.parseBigInt(row.eps()))
                            .sps(FundamentalValueParser.parseBigInt(row.sps()))
                            .bps(FundamentalValueParser.parseBigInt(row.bps()))
                            .retentionRate(FundamentalValueParser.parseDecimal(row.rsrvRate()))
                            .debtRatio(FundamentalValueParser.parseDecimal(row.lbltRate()))
                            .build();
            financialRepository.insertIgnoreDuplicate(entity);
            return true;
        } catch (NumberFormatException | ArithmeticException e) {
            // 파싱 실패·DECIMAL 정수부 경계 초과·BIGINT 비0 소수부·long 범위 초과 → 건별 skip (REQ-BATCH4-070a)
            log.warn(
                    "[financial-ratio] 검증 실패 (건별 skip) — symbol={}, periodType={}, stacYymm={}, reason={}",
                    symbol,
                    periodType,
                    row.stacYymm(),
                    e.getMessage());
            return false;
        }
    }

    /** {@code stac_yymm}(YYYYMM 6자)을 해당 월 1일({@code YYYY-MM-01})로 변환한다. 파싱 불가 시 null. */
    private LocalDate parsePeriodDate(String stacYymm) {
        String trimmed = stacYymm.trim();
        if (trimmed.length() != STAC_YYMM_LENGTH) {
            return null;
        }
        try {
            int year = Integer.parseInt(trimmed.substring(0, 4));
            int month = Integer.parseInt(trimmed.substring(4, 6));
            return LocalDate.of(year, month, 1);
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }
}
