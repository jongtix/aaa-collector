package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository.ShortInterestSnapshot;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 미국 공매도 일별 수집 서비스 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001).
 *
 * <p>FINRA REST Query API 2종({@code regShoDaily} Daily / {@code consolidatedShortInterest} Short
 * Interest)에서 받은 행을 미국 활성 STOCK+ETF 종목({@code findAllActiveOverseasTradable})에 매칭하여 {@code
 * short_sale_overseas}에 소스별 컬럼으로 UPSERT한다. KIS 게이트를 경유하지 않고 {@link FinraShortSaleClient}를 직접
 * 사용한다(REQ-SSO-006).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 공매도 수집 진입점 — FINRA Daily 합산·심볼 정규화 매칭·검증·소스별 UPSERT 담당
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001 REQ-SSO-003,-011,-012,-020,-021 — FINRA 두 소스가
// 수렴하는 수집 진입점
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
public class ShortSaleOverseasCollectionService {

    /** Short Interest 범위 폴링 룩백 일수 — 반월 주기 + 발행 래그(~2주)를 덮는다(D16). */
    private static final int INTEREST_LOOKBACK_DAYS = 40;

    /** Short Interest 페이지 단위 스트리밍 처리 크기(NAS 8GB 힙 보호, D19/D-NEW-6). */
    private static final int INTEREST_PROCESS_BATCH_SIZE = 5000;

    /** FINRA revisionFlag 값 — 직전 사이클 잔고 수정. */
    private static final String REVISION_FLAG = "R";

    private final FinraShortSaleClient finraClient;
    private final StockRepository stockRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;

    /**
     * FINRA Daily 공매도 거래량을 수집한다. reportingFacility 다중 행을 종목·거래일당 합산(REQ-SSO-011)하고, 미국 활성 STOCK+ETF
     * 종목에 매칭(REQ-SSO-012)하여 {@code short_volume}/{@code total_volume}/{@code daily_collected_at}을
     * UPSERT한다. forward(LOCF) 병합은 T5에서 연결한다.
     *
     * @param tradeReportDate FINRA Daily 기준 거래일({@code tradeReportDate} EQUAL)
     * @return 시도/성공/skip 집계
     */
    public DailyResult collectDaily(LocalDate tradeReportDate) {
        List<FinraRegShoDailyResponse> rows = finraClient.fetchRegShoDaily(tradeReportDate);
        if (rows.isEmpty()) {
            // REQ-SSO-020: 빈 응답(휴장일·미발표) — 적재 0건 정상 skip, 예외 없음
            log.info("[overseas-shortsale-daily] 빈 응답 — 적재 0건 skip, tradeDate={}", tradeReportDate);
            return new DailyResult(0, 0, 0);
        }

        Map<String, Stock> stockBySymbol = activeUsStocksBySymbol();

        // reportingFacility 다중 행을 (정규화 심볼) 기준으로 누적 — 미국 활성 종목에 매칭되는 심볼만 대상(REQ-SSO-003/-012)
        Map<String, List<FinraRegShoDailyResponse>> matchedRowsBySymbol =
                rows.stream()
                        .filter(
                                row ->
                                        stockBySymbol.containsKey(
                                                FinraSymbolNormalizer.normalize(row.symbol())))
                        .collect(
                                Collectors.groupingBy(
                                        row -> FinraSymbolNormalizer.normalize(row.symbol()),
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        // D5: 종목별 단건 forward 루프 금지 — 매칭 종목 stockId 집합으로 배치 1쿼리(N+1 회피)
        Set<Long> matchedStockIds =
                matchedRowsBySymbol.keySet().stream()
                        .map(symbol -> stockBySymbol.get(symbol).getId())
                        .collect(Collectors.toSet());
        Map<Long, ShortInterestSnapshot> forwardByStockId =
                shortSaleOverseasRepository.findLatestShortInterestByStockIds(
                        matchedStockIds, tradeReportDate);

        int succeeded = 0;
        int skipped = 0;
        for (Map.Entry<String, List<FinraRegShoDailyResponse>> entry :
                matchedRowsBySymbol.entrySet()) {
            Stock stock = stockBySymbol.get(entry.getKey());
            ShortInterestSnapshot forward = forwardByStockId.get(stock.getId());
            if (upsertAggregated(stock, tradeReportDate, entry.getValue(), forward)) {
                succeeded++;
            } else {
                skipped++;
            }
        }

        int attempted = matchedRowsBySymbol.size();
        log.info(
                "[overseas-shortsale-daily] 수집 완료 — attempted={}, succeeded={}, skipped={}, tradeDate={}",
                attempted,
                succeeded,
                skipped,
                tradeReportDate);
        return new DailyResult(attempted, succeeded, skipped);
    }

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
            // REQ-SSO-020: 빈 응답 — 적재 0건 정상 skip, 예외 없음
            log.info("[overseas-shortsale-interest] 빈 응답 — 적재 0건 skip, range={}~{}", from, today);
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
                toNonNegativeLong(
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
     * 한 종목의 시설 행들을 합산해 UPSERT한다. 행 중 하나라도 음수·소수부(무손실 long 변환 불가)·파싱 실패면 종목 단위
     * skip+WARN(REQ-SSO-021).
     *
     * @return 적재했으면 {@code true}, skip이면 {@code false}
     */
    private boolean upsertAggregated(
            Stock stock,
            LocalDate tradeReportDate,
            List<FinraRegShoDailyResponse> facilityRows,
            ShortInterestSnapshot forward) {
        long shortVolume = 0;
        long totalVolume = 0;
        List<String> reasons = new ArrayList<>();
        for (FinraRegShoDailyResponse row : facilityRows) {
            Long shortQty = toNonNegativeLong(row.shortParQuantity(), "shortParQuantity", reasons);
            Long totalQty = toNonNegativeLong(row.totalParQuantity(), "totalParQuantity", reasons);
            if (shortQty == null || totalQty == null) {
                continue;
            }
            shortVolume += shortQty;
            totalVolume += totalQty;
        }

        if (!reasons.isEmpty()) {
            // REQ-SSO-021: 데이터 유실 가시화 — 잘못된 시설 행이 있으면 종목 합산 무결성을 위해 종목 단위 skip
            log.warn(
                    "[overseas-shortsale-daily] 검증 실패로 종목 skip — symbol={}, tradeDate={}, reasons={}",
                    stock.getSymbol(),
                    tradeReportDate,
                    reasons);
            return false;
        }

        // REQ-SSO-013/-013a(LOCF): forward 매칭이 있으면 short_interest 동반 적재, 없으면 null로 interest 컬럼 미변경
        Long forwardInterest = forward == null ? null : forward.shortInterest();
        LocalDate forwardInterestDate = forward == null ? null : forward.shortInterestDate();
        shortSaleOverseasRepository.upsertDaily(
                stock.getId(),
                tradeReportDate,
                shortVolume,
                totalVolume,
                LocalDateTime.now(),
                forwardInterest,
                forwardInterestDate);
        return true;
    }

    /**
     * FINRA 수량({@link BigDecimal})을 음수 아님·무손실 {@code long}으로 변환한다. 음수·소수부가 있으면(longValueExact 실패)
     * {@code reasons}에 사유를 누적하고 {@code null}을 반환한다(BATCH-004 무손실 변환 선례 정합).
     */
    private Long toNonNegativeLong(BigDecimal value, String field, List<String> reasons) {
        if (value == null) {
            reasons.add(field + "=null");
            return null;
        }
        if (value.signum() < 0) {
            reasons.add(field + "<0(" + value.toPlainString() + ")");
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            reasons.add(field + " 소수부 존재(" + value.toPlainString() + ")");
            return null;
        }
    }

    /**
     * Daily 수집 결과 집계.
     *
     * @param attempted 매칭된 시도 종목 수
     * @param succeeded 적재 성공 종목 수
     * @param skipped 검증 실패 등으로 skip한 종목 수
     */
    public record DailyResult(int attempted, int succeeded, int skipped) {}

    /**
     * Short Interest 수집 결과 집계.
     *
     * @param attempted 매칭된 시도 행 수
     * @param succeeded 적재(신규/갱신) 성공 행 수
     * @param skipped 기존 비revision·검증 실패 등으로 skip한 행 수
     */
    public record InterestResult(int attempted, int succeeded, int skipped) {}
}
