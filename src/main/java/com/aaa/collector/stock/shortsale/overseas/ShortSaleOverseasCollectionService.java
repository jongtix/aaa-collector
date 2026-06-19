package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.stock.ShortSaleOverseasRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        int succeeded = 0;
        int skipped = 0;
        for (Map.Entry<String, List<FinraRegShoDailyResponse>> entry :
                matchedRowsBySymbol.entrySet()) {
            Stock stock = stockBySymbol.get(entry.getKey());
            if (upsertAggregated(stock, tradeReportDate, entry.getValue())) {
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
            Stock stock, LocalDate tradeReportDate, List<FinraRegShoDailyResponse> facilityRows) {
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

        // T5에서 forward(LOCF) 매칭값을 주입한다 — 현재는 null로 interest 컬럼 미변경(REQ-SSO-013a)
        shortSaleOverseasRepository.upsertDaily(
                stock.getId(),
                tradeReportDate,
                shortVolume,
                totalVolume,
                LocalDateTime.now(),
                null,
                null);
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
}
