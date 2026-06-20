package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 수집(D16)하고, 페이지(5000행) 단위 스트리밍 처리로 전 행 동시 상주를 피한다(D-NEW-6). Daily 컬럼은 보존한다(REQ-SSO-015/-022).
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

    /** Short Interest 페이지 단위 스트리밍 처리 크기(NAS 8GB 힙 보호, D19/D-NEW-6). */
    private static final int INTEREST_PROCESS_BATCH_SIZE = 5000;

    /** FINRA revisionFlag 값 — 직전 사이클 잔고 수정. */
    private static final String REVISION_FLAG = "R";

    /** BatchMetrics 배치 라벨 — Short Interest 경로. */
    private static final String BATCH_INTEREST = "overseas-shortsale-interest";

    private final FinraShortSaleClient finraClient;
    private final StockRepository stockRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final BatchMetrics batchMetrics;

    /**
     * FINRA Short Interest 잔고를 {@code dateRangeFilters}(settlementDate, 오늘−40일~오늘) 범위로 수집한다(D16).
     * 미국 활성 STOCK+ETF 종목에 매칭(REQ-SSO-003)하여 {@code trade_date=settlementDate} 행으로 interest 전용 컬럼만
     * UPSERT한다(REQ-SSO-015/-022). DB 미적재 settlementDate는 신규 적재하고, 이미 적재됐어도 {@code
     * revisionFlag="R"}이면 갱신, {@code ≠"R"}이면 skip한다(REQ-SSO-014a/-014b/-014c). 페이지(5000행) 단위로 스트리밍
     * 처리해 전 행 동시 상주를 피한다(D-NEW-6).
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
        // REQ-SSO-014a: DB 기존 settlementDate 집합 — 미적재 차집합 + revision 판정에 사용
        Set<LocalDate> existingSettlementDates =
                new HashSet<>(shortSaleOverseasRepository.findExistingSettlementDates(from, today));

        int attempted = 0;
        int succeeded = 0;
        int skipped = 0;
        // D-NEW-6: 페이지(5000행) 단위 스트리밍 처리 — 전 행 동시 적재 금지(NAS 8GB)
        for (int start = 0; start < rows.size(); start += INTEREST_PROCESS_BATCH_SIZE) {
            int end = Math.min(start + INTEREST_PROCESS_BATCH_SIZE, rows.size());
            for (FinraConsolidatedShortInterestResponse row : rows.subList(start, end)) {
                Stock stock = stockBySymbol.get(FinraSymbolNormalizer.normalize(row.symbolCode()));
                if (stock == null) {
                    // 미매칭(국내·미존재) — 적재 대상 아님(대량 미매칭은 정상)
                    continue;
                }
                attempted++;
                if (upsertInterestRow(stock, row, existingSettlementDates)) {
                    succeeded++;
                } else {
                    skipped++;
                }
            }
        }

        // REQ-SSO-040: 시도/성공/실패/skip 집계 계측. fail은 attempted-success-skip로 유도
        batchMetrics.recordCompletion(
                BATCH_INTEREST,
                attempted,
                succeeded,
                Math.max(0L, (long) attempted - succeeded - skipped),
                skipped);
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
     * Short Interest 한 행을 UPSERT 한다. 미적재 settlementDate는 신규 적재(REQ-SSO-014a), 이미 적재됐으면 {@code
     * revisionFlag="R"}일 때만 갱신(REQ-SSO-014b), {@code ≠"R"}이면 skip(REQ-SSO-014c). 잔고가 null·음수·소수부면
     * skip+WARN(REQ-SSO-021).
     */
    private boolean upsertInterestRow(
            Stock stock,
            FinraConsolidatedShortInterestResponse row,
            Set<LocalDate> existingSettlementDates) {
        LocalDate settlementDate = row.settlementDate();
        if (settlementDate == null) {
            log.warn(
                    "[overseas-shortsale-interest] settlementDate=null로 skip — symbol={}",
                    stock.getSymbol());
            return false;
        }

        boolean alreadyExists = existingSettlementDates.contains(settlementDate);
        boolean isRevision = REVISION_FLAG.equals(row.revisionFlag());
        if (alreadyExists && !isRevision) {
            // REQ-SSO-014c: 기존 settlementDate + 비revision → 불필요 쓰기 회피
            return false;
        }

        List<String> reasons = new ArrayList<>();
        Long shortInterest =
                FinraQuantityParser.toNonNegativeLong(
                        row.currentShortPositionQuantity(),
                        "currentShortPositionQuantity",
                        reasons);
        if (shortInterest == null) {
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
