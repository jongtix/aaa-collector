package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 미국 공매도 Short Interest(잔고) 수집 서비스 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001).
 *
 * <p>FINRA {@code consolidatedShortInterest} 행을 미국 활성 STOCK+ETF 종목에 매칭하여 {@code
 * short_sale_overseas}의 interest 전용 컬럼({@code short_interest}, {@code short_interest_date}, {@code
 * interest_collected_at})을 UPSERT한다. {@code dateRangeFilters}(settlementDate, 오늘−40일~오늘) 범위로
 * 수집(D16)하고, FINRA 전량({@code fetchAllPages} 누적) 수신 후 5000행 단위로 청크 처리한다. Daily 컬럼은
 * 보존한다(REQ-SSO-015/-022).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 공매도 Short Interest 수집 진입점 — FINRA consolidatedShortInterest 범위폴링·revision
// 판정·Interest
// UPSERT·계측 담당
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
// REQ-SSO-003,-014a,-014b,-014c,-015,-020,-021,-022,-040
// — Interest 경로 수렴 진입점(FinraShortSaleClient, StockRepository, ShortSaleOverseasRepository,
// BatchMetrics)
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
public class ShortSaleOverseasInterestCollectionService {

    /** Short Interest 범위 폴링 룩백 일수 — 반월 주기 + 발행 래그(~2주)를 덮는다(D16). */
    private static final int INTEREST_LOOKBACK_DAYS = 40;

    /** Short Interest 청크 처리 크기 — 전량 누적 후 이 단위로 나눠 처리한다(D19). */
    private static final int INTEREST_PROCESS_BATCH_SIZE = 5000;

    /** FINRA revisionFlag 값 — 직전 사이클 잔고 수정. */
    private static final String REVISION_FLAG = "R";

    /** BatchMetrics 배치 라벨 — Short Interest 경로. */
    private static final String BATCH_INTEREST = "overseas-shortsale-interest";

    private final FinraShortSaleClient finraClient;
    private final StockRepository stockRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final BatchMetrics batchMetrics;
    private final WatermarkMetrics watermarkMetrics;

    /**
     * FINRA Short Interest 잔고를 {@code dateRangeFilters}(settlementDate, 오늘−40일~오늘) 범위로 수집한다(D16).
     * 미국 활성 STOCK+ETF 종목에 매칭(REQ-SSO-003)하여 {@code trade_date=settlementDate} 행으로 interest 전용 컬럼만
     * UPSERT한다(REQ-SSO-015/-022). DB에 이미 적재된 {@code (stock_id, short_interest_date)} 쌍이 없으면 신규 적재,
     * 있으면 {@code revisionFlag="R"}일 때만 갱신, {@code ≠"R"}이면 skip한다(REQ-SSO-014a/-014b/-014c).
     *
     * @param today 수집 기준일(범위 끝)
     * @return 시도/성공/skip 집계
     */
    public InterestResult collectShortInterest(LocalDate today) {
        LocalDate from = today.minusDays(INTEREST_LOOKBACK_DAYS);
        List<FinraConsolidatedShortInterestResponse> rows =
                finraClient.fetchConsolidatedShortInterest(from, today);
        if (rows.isEmpty()) {
            // REQ-SSO-020/-030: 내용까지 본 결과 빈 응답 — 적재 0건 정상 skip. 0건도 계측한다(REQ-SSO-040)
            log.info("[overseas-shortsale-interest] 빈 응답 — 적재 0건 skip, range={}~{}", from, today);
            batchMetrics.recordCompletion(BATCH_INTEREST, 0L, 0L, 0L, 0L);
            return new InterestResult(0, 0, 0);
        }

        Map<String, Stock> stockBySymbol = activeUsStocksBySymbol();
        // REQ-SSO-014a: (stock_id, short_interest_date) 쌍 단위 존재 판정 — 전역 날짜 집합이면 교차 종목 침묵 드롭 발생
        Set<Long> stockIds =
                stockBySymbol.values().stream()
                        .map(Stock::getId)
                        .collect(Collectors.toUnmodifiableSet());
        Map<Long, Set<LocalDate>> existingPairs =
                shortSaleOverseasRepository.findExistingInterestPairsByStockIds(
                        stockIds, from, today);

        InterestBatchAccumulator acc = new InterestBatchAccumulator();
        for (int start = 0; start < rows.size(); start += INTEREST_PROCESS_BATCH_SIZE) {
            int end = Math.min(start + INTEREST_PROCESS_BATCH_SIZE, rows.size());
            for (FinraConsolidatedShortInterestResponse row : rows.subList(start, end)) {
                processInterestRow(row, stockBySymbol, existingPairs, acc);
            }
        }
        int attempted = acc.attempted;
        int succeeded = acc.succeeded;
        int skipped = acc.skipped;

        // REQ-SSO-040: 시도/성공/실패/skip 집계 계측. fail은 attempted-success-skip로 유도
        batchMetrics.recordCompletion(
                BATCH_INTEREST,
                attempted,
                succeeded,
                Math.max(0L, (long) attempted - succeeded - skipped),
                skipped);
        // REQ-XR-016(DP-5): 실데이터(success>0) 도착 시에만 last_data 갱신 — 빈 응답/전량 skip은 미갱신하여
        // "FINRA 미발표/파싱 파손"을 실행 stamp(last_load)와 분리해 탐지 가능하게 한다.
        if (succeeded > 0) {
            batchMetrics.recordDataArrival(BATCH_INTEREST);
        }
        // SPEC-OBSV-WATERMARK-001 REQ-WM-001: 성공 upsert된 행들의 최대 settlementDate로 forward-only 갱신
        watermarkMetrics.advance(
                WatermarkSeries.SHORT_SALE_OVERSEAS_INTEREST, acc.maxSettlementDate);
        log.info(
                "[overseas-shortsale-interest] 수집 완료 — attempted={}, succeeded={}, skipped={}, range={}~{}",
                attempted,
                succeeded,
                skipped,
                from,
                today);
        return new InterestResult(attempted, succeeded, skipped);
    }

    /**
     * 한 행을 처리해 매칭·UPSERT 결과를 누산기에 반영한다. 미매칭 심볼은 카운트하지 않는다(대량 미매칭은 정상). 성공 upsert된 행의 {@code
     * settlementDate}로 {@link InterestBatchAccumulator#maxSettlementDate}를
     * 전진시킨다(SPEC-OBSV-WATERMARK-001 REQ-WM-001).
     */
    private void processInterestRow(
            FinraConsolidatedShortInterestResponse row,
            Map<String, Stock> stockBySymbol,
            Map<Long, Set<LocalDate>> existingPairs,
            InterestBatchAccumulator acc) {
        Stock stock = stockBySymbol.get(FinraSymbolNormalizer.normalize(row.symbolCode()));
        if (stock == null) {
            // 미매칭(국내·미존재) — 적재 대상 아님(대량 미매칭은 정상)
            return;
        }
        acc.attempted++;
        if (upsertInterestRow(stock, row, existingPairs)) {
            acc.succeeded++;
            LocalDate settlementDate = row.settlementDate();
            if (acc.maxSettlementDate == null || settlementDate.isAfter(acc.maxSettlementDate)) {
                acc.maxSettlementDate = settlementDate;
            }
        } else {
            acc.skipped++;
        }
    }

    /** {@link #collectShortInterest(LocalDate)} 청크 순회 결과 누산기. */
    private static final class InterestBatchAccumulator {
        int attempted;
        int succeeded;
        int skipped;
        LocalDate maxSettlementDate;
    }

    /**
     * Short Interest 한 행을 UPSERT 한다. {@code (stock_id, settlementDate)} 쌍이 미적재이면 신규
     * 적재(REQ-SSO-014a), 이미 적재됐으면 {@code revisionFlag="R"}일 때만 갱신(REQ-SSO-014b), {@code ≠"R"}이면
     * skip(REQ-SSO-014c). 잔고가 null·음수·scale 초과·소수부면 skip+WARN하고 파싱 거부 카운터를
     * 증가시킨다(REQ-SSO-021·REQ-SSD-016).
     */
    private boolean upsertInterestRow(
            Stock stock,
            FinraConsolidatedShortInterestResponse row,
            Map<Long, Set<LocalDate>> existingPairs) {
        LocalDate settlementDate = row.settlementDate();
        if (settlementDate == null) {
            log.warn(
                    "[overseas-shortsale-interest] settlementDate=null로 skip — symbol={}",
                    stock.getSymbol());
            return false;
        }

        // (stock_id, settlementDate) 쌍 단위 존재 판정 — 전역 날짜 집합이면 교차 종목 침묵 드롭 발생(MA-01)
        Set<LocalDate> stockDates = existingPairs.get(stock.getId());
        boolean alreadyExists = stockDates != null && stockDates.contains(settlementDate);
        boolean isRevision = REVISION_FLAG.equals(row.revisionFlag());
        if (alreadyExists && !isRevision) {
            // REQ-SSO-014c: 기존 (stock_id, settlementDate) 쌍 + 비revision → 불필요 쓰기 회피
            return false;
        }

        List<String> reasons = new ArrayList<>();
        // REQ-SSD-016: short_interest는 BIGINT 유지(대조군 전건 정수). 소수부가 있으면(희소 조건) 조용히 버리지 않고
        // 정수 검증 래퍼가 거부 사유를 누적 → 파싱 거부 카운터로 관측 가능한 신호화(침묵 skip 방지).
        Long shortInterest =
                FinraQuantityParser.toNonNegativeInteger(
                        row.currentShortPositionQuantity(),
                        "currentShortPositionQuantity",
                        reasons);
        if (shortInterest == null) {
            // REQ-SSD-009/016: 파싱 거부를 last_load와 독립적인 카운터로 계측
            batchMetrics.recordParseRejections(BATCH_INTEREST, reasons.size());
            log.warn(
                    "[overseas-shortsale-interest] 검증 실패로 skip — symbol={}, settlementDate={}, reasons={}",
                    stock.getSymbol(),
                    settlementDate,
                    reasons);
            return false;
        }

        // REQ-SSO-015/-022: interest 전용 컬럼만 SET(Daily 컬럼 보존, daily_collected_at NULL 유지),
        // float_shares/si_pct_float는 미적재로 NULL(REQ-SSO-004)
        shortSaleOverseasRepository.upsertInterest(
                stock.getId(), settlementDate, shortInterest, LocalDateTime.now());
        return true;
    }

    /** 활성 미국 STOCK+ETF 종목을 {@code stocks.symbol → Stock} 맵으로 만든다(평문 티커가 키). */
    private Map<String, Stock> activeUsStocksBySymbol() {
        return stockRepository.findAllActiveOverseasTradable().stream()
                .collect(Collectors.toMap(Stock::getSymbol, Function.identity(), (a, b) -> a));
    }

    /**
     * Short Interest 수집 결과 집계.
     *
     * @param attempted 매칭된 시도 행 수
     * @param succeeded 적재(신규/갱신) 성공 행 수
     * @param skipped 기존 비revision·검증 실패 등으로 skip한 행 수
     */
    public record InterestResult(int attempted, int succeeded, int skipped) {}
}
