package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.observability.WatermarkSeries;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
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
 * 미국 공매도 Short Interest(잔고) 수집 서비스 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001, 전 보존 구간 폴링 전환은
 * SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M3).
 *
 * <p>FINRA {@code consolidatedShortInterest} 행을 {@code domainFilters}(활성 해외 워치리스트 심볼) + {@code
 * dateRangeFilters}(settlementDate, 2017-12-29~오늘 전 보존 구간) 범위로 수집해 미국 활성 STOCK+ETF 종목에 매칭하고, {@code
 * short_sale_overseas}의 interest 전용 컬럼({@code short_interest}, {@code short_interest_date}, {@code
 * days_to_cover}, {@code avg_daily_volume}, {@code interest_collected_at})을 UPSERT한다. FINRA
 * 전량({@code fetchAllPages} 누적) 수신 후 5000행 단위로 청크 처리한다. Daily 컬럼은 보존한다(REQ-SSO-015/-022).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 공매도 Short Interest 수집 진입점 — FINRA consolidatedShortInterest 범위폴링·revision
// 판정·Interest
// UPSERT·계측 담당
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
// REQ-SSO-003,-014a,-014b,-014c,-015,-020,-021,-022,-040
// + SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 REQ-SSOI-001,-002,-003,-010,-011 (M3 전 보존구간 domainFilters
// 전환)
// — Interest 경로 수렴 진입점(FinraShortSaleClient, StockRepository, ShortSaleOverseasRepository,
// BatchMetrics)
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
public class ShortSaleOverseasInterestCollectionService {

    /**
     * Interest 경로 상장일 게이트의 "최근 구간" 경계 일수(REQ-SSOI-006/-007, plan.md 핵심 아키텍처 결정 1). M2까지는 Short
     * Interest 범위 폴링 룩백(반월 주기 + 발행 래그 ~2주를 덮는 용도, D16)으로 쓰였으나, M3부터 폴링 범위 산정은 {@link
     * #INTEREST_RETENTION_FLOOR}가 대체하고 이 상수는 게이트 "최근 구간" 경계 판정 전용으로 재해석된다(사용자 결정 2026-08-02) —
     * 상수명·값은 변경하지 않는다.
     */
    private static final int INTEREST_LOOKBACK_DAYS = 40;

    /**
     * Short Interest 전 보존 구간 폴링 하한(SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M3, 핵심 아키텍처 결정 3). FINRA
     * API 실측 최고(最古) settlementDate(2026-07-28 실측, api-specs/finra/01-공매도잔고.md) — FINRA 자체 보존 정책이라
     * 배포 후 변경될 성질이 아니므로 config로 외부화하지 않는다(YAGNI, INTEREST_LOOKBACK_DAYS와 동일 배치 패턴).
     */
    private static final LocalDate INTEREST_RETENTION_FLOOR = LocalDate.of(2017, 12, 29);

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
     * FINRA Short Interest 잔고를 {@code domainFilters}(활성 해외 워치리스트 심볼) + {@code dateRangeFilters}
     * (settlementDate, 2017-12-29~오늘 전 보존 구간) 범위로 수집한다(SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 M3,
     * REQ-SSOI-001/-002 — 舊 40일 룩백 폴링에서 전환). 미국 활성 STOCK+ETF 종목에 매칭(REQ-SSO-003)하여 {@code
     * trade_date=settlementDate} 행으로 interest 전용 컬럼만 UPSERT한다(REQ-SSO-015/-022). DB에 이미 적재된 {@code
     * (stock_id, short_interest_date)} 쌍이 없으면 신규 적재, 있으면 {@code revisionFlag="R"}일 때만 갱신, {@code
     * ≠"R"}이면 skip한다(REQ-SSO-014a/-014b/-014c). Interest 경로 상장일 게이트(REQ-SSOI-005/-006/-007)는 매칭 직후
     * 별도로 판정한다.
     *
     * @param today 수집 기준일(범위 끝)
     * @return 시도/성공/skip 집계
     */
    public InterestResult collectShortInterest(LocalDate today) {
        // REQ-SSOI-001/-002: domainFilters(심볼 목록)를 구성하려면 요청 전에 워치리스트를 먼저 조회해야 한다 — 매칭
        // 전 전량 수신하던 기존 순서를 뒤집는다(plan.md 핵심 아키텍처 결정 3).
        Map<String, Stock> stockBySymbol = activeUsStocksBySymbol();
        List<FinraConsolidatedShortInterestResponse> rows =
                finraClient.fetchConsolidatedShortInterestForSymbols(
                        stockBySymbol.keySet(), INTEREST_RETENTION_FLOOR, today);
        if (rows.isEmpty()) {
            // REQ-SSO-020/-030: 내용까지 본 결과 빈 응답 — 적재 0건 정상 skip. 0건도 계측한다(REQ-SSO-040)
            log.info(
                    "[overseas-shortsale-interest] 빈 응답 — 적재 0건 skip, range={}~{}",
                    INTEREST_RETENTION_FLOOR,
                    today);
            batchMetrics.recordCompletion(BATCH_INTEREST, 0L, 0L, 0L, 0L);
            return new InterestResult(0, 0, 0);
        }

        // REQ-SSO-014a: (stock_id, short_interest_date) 쌍 단위 존재 판정 — 전역 날짜 집합이면 교차 종목 침묵 드롭 발생
        Set<Long> stockIds =
                stockBySymbol.values().stream()
                        .map(Stock::getId)
                        .collect(Collectors.toUnmodifiableSet());
        Map<Long, Set<LocalDate>> existingPairs =
                shortSaleOverseasRepository.findExistingInterestPairsByStockIds(
                        stockIds, INTEREST_RETENTION_FLOOR, today);

        InterestBatchAccumulator acc = new InterestBatchAccumulator();
        for (int start = 0; start < rows.size(); start += INTEREST_PROCESS_BATCH_SIZE) {
            int end = Math.min(start + INTEREST_PROCESS_BATCH_SIZE, rows.size());
            for (FinraConsolidatedShortInterestResponse row : rows.subList(start, end)) {
                processInterestRow(row, stockBySymbol, existingPairs, today, acc);
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
        // REQ-SSOI-008: Interest 게이트 스킵 건수를 0건이어도 무조건 1회 기록해 0 시계열을 노출한다(recordTickerReuseSkips
        // 형판 — CDN Daily는 loadDate() 종료 시 gateExcluded를 조건 없이 기록한다).
        batchMetrics.recordInterestGateSkips(acc.gateSkipped);
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
                INTEREST_RETENTION_FLOOR,
                today);
        return new InterestResult(attempted, succeeded, skipped);
    }

    /**
     * 한 행을 처리해 매칭·UPSERT 결과를 누산기에 반영한다. 미매칭 심볼은 카운트하지 않는다(대량 미매칭은 정상). 성공 upsert된 행의 {@code
     * settlementDate}로 {@link InterestBatchAccumulator#maxSettlementDate}를
     * 전진시킨다(SPEC-OBSV-WATERMARK-001 REQ-WM-001). Interest 경로 상장일 게이트(REQ-SSOI-005/-006/-007)는 매칭
     * 직후·UPSERT 호출 전에 판정한다 — revision 여부와 무관하게 제외하기 위해 {@link #upsertInterestRow(Stock,
     * FinraConsolidatedShortInterestResponse, Map)}의 revision 판정보다 먼저 검사한다.
     */
    private void processInterestRow(
            FinraConsolidatedShortInterestResponse row,
            Map<String, Stock> stockBySymbol,
            Map<Long, Set<LocalDate>> existingPairs,
            LocalDate today,
            InterestBatchAccumulator acc) {
        Stock stock = stockBySymbol.get(FinraSymbolNormalizer.normalize(row.symbolCode()));
        if (stock == null) {
            // 미매칭(국내·미존재) — 적재 대상 아님(대량 미매칭은 정상)
            return;
        }
        acc.attempted++;
        LocalDate settlementDate = row.settlementDate();
        if (settlementDate != null && isGatedOut(settlementDate, today, stock)) {
            // REQ-SSOI-005/-006/-007: Interest 경로 상장일 게이트 — CDN Daily 게이트(FinraCdnDailyLoaderImpl)와
            // 별개
            // 지점(spec.md C1). revision 판정보다 먼저 검사해 舊 소유자 구간이 revisionFlag="R" 경로로 재유입되지 않게 한다.
            acc.gateSkipped++;
            acc.skipped++;
            log.info(
                    "[overseas-shortsale-interest] 상장일 게이트 제외 — symbol={}, settlementDate={}, listedDate={}",
                    stock.getSymbol(),
                    settlementDate,
                    stock.getListedDate());
            return;
        }
        if (upsertInterestRow(stock, row, existingPairs)) {
            acc.succeeded++;
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
        int gateSkipped;
        LocalDate maxSettlementDate;
    }

    /**
     * Short Interest 한 행을 UPSERT 한다. {@code (stock_id, settlementDate)} 쌍이 미적재이면 신규
     * 적재(REQ-SSO-014a), 이미 적재됐으면 {@code revisionFlag="R"}일 때만 갱신(REQ-SSO-014b), {@code ≠"R"}이면
     * skip(REQ-SSO-014c). 잔고가 null·음수·scale 초과·소수부면 skip+WARN하고 파싱 거부 카운터를
     * 증가시킨다(REQ-SSO-021·REQ-SSD-016). Interest 경로 상장일 게이트 판정(REQ-SSOI-005/-006/-007)은 호출자({@link
     * #processInterestRow})가 이 메서드 호출 전에 이미 수행한다 — revisionFlag="R" 경로가 舊 소유자 구간을 재유입시키지 않도록
     * revision 판정보다 먼저 걸러진다. {@code daysToCoverQuantity}/{@code averageDailyVolumeQuantity}는 관대하게
     * 파싱해 {@code days_to_cover}/{@code avg_daily_volume}로 함께 UPSERT한다 — 없거나 파싱 불가해도 행을 거부하지 않고 해당
     * 컬럼만 NULL로 남긴다(REQ-SSOI-010/-011).
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

        // REQ-SSOI-011: daysToCoverQuantity/averageDailyVolumeQuantity는 관대하게 파싱한다 — 없거나 파싱 불가해도
        // 행을 거부하지 않고 해당 컬럼만 NULL로 남긴다(currentShortPositionQuantity의 엄격 검증과 다름).
        BigDecimal daysToCover =
                FinraQuantityParser.toNullableNonNegativeDecimal(row.daysToCoverQuantity());
        Long avgDailyVolume =
                FinraQuantityParser.toNullableNonNegativeInteger(row.averageDailyVolumeQuantity());

        // REQ-SSO-015/-022: interest 전용 컬럼만 SET(Daily 컬럼 보존, daily_collected_at NULL 유지),
        // float_shares/si_pct_float는 미적재로 NULL(REQ-SSO-004)
        shortSaleOverseasRepository.upsertInterest(
                stock.getId(),
                settlementDate,
                shortInterest,
                LocalDateTime.now(),
                daysToCover,
                avgDailyVolume);
        return true;
    }

    /**
     * Interest 경로 상장일 게이트를 판정한다 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-003 REQ-SSOI-005/-006/-007,
     * plan.md 핵심 아키텍처 결정 1). {@code listedDate}가 확정(non-null)이면 {@code settlementDate}가 그보다 이르면
     * 제외한다(舊 소유자 티커재사용 오염 — SERV 실측 근거, spec.md §1.2). {@code listedDate}가 미상(null)이면 CDN Daily
     * 게이트({@link com.aaa.collector.stock.shortsale.overseas.backfill.FinraCdnDailyLoaderImpl},
     * 무게이트)와 의도적으로 다르게 최근 구간(오늘 − {@value #INTEREST_LOOKBACK_DAYS}일)은 통과시키고 그보다 오래된 구간은 방어적으로 제외한다
     * — 전 보존 구간(최대 8.6년)을 한 번에 폴링하는 이 경로는 신원 불확실 상태로 장기간을 무비판 적재할 위험이 CDN Daily(하루치 폴링)보다 크기
     * 때문이다(사용자 결정 2026-08-02).
     *
     * @param settlementDate 수신 행의 정산일
     * @param today 수집 기준일(범위 끝, 최근 구간 경계 산정 기준)
     * @param stock 매칭된 종목
     * @return 이 행을 적재에서 제외해야 하면 {@code true}
     */
    private boolean isGatedOut(LocalDate settlementDate, LocalDate today, Stock stock) {
        LocalDate listedDate = stock.getListedDate();
        if (listedDate != null) {
            // REQ-SSOI-005: 상장일 확정 — 상장일보다 이른 행은 舊 소유자 구간으로 간주해 제외
            return settlementDate.isBefore(listedDate);
        }
        // REQ-SSOI-006/-007: 상장일 미상 — 최근 구간(기존 INTEREST_LOOKBACK_DAYS 재사용, 역할 전환)은 통과, 그 이전은
        // 방어적 차단. 경계(settlementDate == recentWindowStart)는 isBefore=false이므로 통과(AC-07a).
        LocalDate recentWindowStart = today.minusDays(INTEREST_LOOKBACK_DAYS);
        return settlementDate.isBefore(recentWindowStart);
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
