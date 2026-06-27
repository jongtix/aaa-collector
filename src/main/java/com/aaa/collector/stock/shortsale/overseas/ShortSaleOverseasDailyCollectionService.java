package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.ShortSaleOverseasRepository.ShortInterestSnapshot;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * 미국 공매도 Daily(거래량) 수집 서비스 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001).
 *
 * <p>FINRA {@code regShoDaily} 행을 미국 활성 STOCK+ETF 종목에 매칭하여 {@code short_sale_overseas}의 daily
 * 컬럼({@code short_volume}, {@code total_volume}, {@code daily_collected_at})을 UPSERT한다.
 * reportingFacility 다중 행을 종목·거래일당 합산(REQ-SSO-011)하고, forward(LOCF) 병합을 단건 배치 쿼리로 처리한다(REQ-SSO-013).
 */
@Slf4j
@Service
@RequiredArgsConstructor
// @MX:ANCHOR: [AUTO] 미국 공매도 Daily 수집 진입점 — FINRA regShoDaily 합산·심볼 정규화 매칭·LOCF 병합·Daily UPSERT·계측
// 담당
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001 REQ-SSO-003,-011,-012,-013,-020,-021,-040 —
// Daily 경로 수렴 진입점(FinraShortSaleClient, StockRepository, ShortSaleOverseasRepository, BatchMetrics)
// @MX:SPEC: SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001
public class ShortSaleOverseasDailyCollectionService {

    /** BatchMetrics 배치 라벨 — Daily 경로. */
    private static final String BATCH_DAILY = "overseas-shortsale-daily";

    private final FinraShortSaleClient finraClient;
    private final StockRepository stockRepository;
    private final ShortSaleOverseasRepository shortSaleOverseasRepository;
    private final BatchMetrics batchMetrics;
    private final UsMarketOpenGate usMarketOpenGate;

    /**
     * FINRA Daily 공매도 거래량을 수집한다. reportingFacility 다중 행을 종목·거래일당 합산(REQ-SSO-011)하고, 미국 활성 STOCK+ETF
     * 종목에 매칭(REQ-SSO-012)하여 {@code short_volume}/{@code total_volume}/{@code daily_collected_at}을
     * UPSERT한다. forward(LOCF) 병합은 배치 1쿼리로 처리한다(REQ-SSO-013).
     *
     * @param tradeReportDate FINRA Daily 기준 거래일({@code tradeReportDate} EQUAL)
     * @return 시도/성공/skip 집계
     */
    public DailyResult collectDaily(LocalDate tradeReportDate) {
        // REQ-USMKT-013: 미장 휴장일 → skip
        if (!usMarketOpenGate.isOpenDay(tradeReportDate)) {
            log.info("[overseas-shortsale-daily] {} 미장 휴장일 → skip", tradeReportDate);
            return new DailyResult(0, 0, 0);
        }

        List<FinraRegShoDailyResponse> rows = finraClient.fetchRegShoDaily(tradeReportDate);
        if (rows.isEmpty()) {
            // REQ-SSO-020/-030: 내용까지 본 결과 빈 응답(휴장일·미발표) — 적재 0건 정상 skip, 예외 없음. 0건도
            // 계측한다(REQ-SSO-040)
            log.info("[overseas-shortsale-daily] 빈 응답 — 적재 0건 skip, tradeDate={}", tradeReportDate);
            batchMetrics.recordCompletion(BATCH_DAILY, 0L, 0L, 0L, 0L);
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
        // REQ-SSO-040: 시도/성공/실패/skip 집계 계측. fail은 attempted-success-skip로 유도(BatchMetrics 컨벤션)
        batchMetrics.recordCompletion(
                BATCH_DAILY,
                attempted,
                succeeded,
                Math.max(0L, (long) attempted - succeeded - skipped),
                skipped);
        log.info(
                "[overseas-shortsale-daily] 수집 완료 — attempted={}, succeeded={}, skipped={}, tradeDate={}",
                attempted,
                succeeded,
                skipped,
                tradeReportDate);
        return new DailyResult(attempted, succeeded, skipped);
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
            Long shortQty =
                    FinraQuantityParser.toNonNegativeLong(
                            row.shortParQuantity(), "shortParQuantity", reasons);
            Long totalQty =
                    FinraQuantityParser.toNonNegativeLong(
                            row.totalParQuantity(), "totalParQuantity", reasons);
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

    /** 활성 미국 STOCK+ETF 종목을 {@code stocks.symbol → Stock} 맵으로 만든다(평문 티커가 키). */
    private Map<String, Stock> activeUsStocksBySymbol() {
        return stockRepository.findAllActiveOverseasTradable().stream()
                .collect(Collectors.toMap(Stock::getSymbol, Function.identity(), (a, b) -> a));
    }

    /**
     * Daily 수집 결과 집계.
     *
     * @param attempted 매칭된 시도 종목 수
     * @param succeeded 적재 성공 종목 수
     * @param skipped 검증 실패 등으로 skip한 종목 수
     */
    public record DailyResult(int attempted, int succeeded, int skipped) {}
}
