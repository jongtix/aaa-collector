package com.aaa.collector.stock;

import com.aaa.collector.backfill.BackfillSeedTargetProvider;
import com.aaa.collector.stock.enums.Market;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link BackfillSeedTargetProvider} 포트의 {@code stock} 측 어댑터 (SPEC-COLLECTOR-BACKFILL-001 T2).
 *
 * <p>활성 관심종목을 {@link StockRepository}로 조회해 시장별로 분류한다 — backfill 시더가 {@code stock} 타입(엔티티·enum)을 직접
 * 의존하지 않도록 분류 책임을 이 어댑터로 역전한다({@code backfill → stock} 순환 의존 회피, MdcArchitectureTest).
 *
 * <ul>
 *   <li>국내: {@link StockRepository#findAllActiveTradable()}(시장 무관 STOCK+ETF)에서 시장이
 *       국내(KOSPI/KOSDAQ)인 종목만 — 미국 종목이 국내 4행 시딩 대상에 섞이는 것을 방지한다(AC-7.3).
 *   <li>미국: {@link StockRepository#findAllActiveOverseasTradable()}(NYSE/NASDAQ/AMEX 한정).
 * </ul>
 */
// @MX:NOTE: [AUTO] 의존성 역전 어댑터 — 시딩 대상 시장 분류를 stock 측에 둬 backfill→stock 순환 의존을 끊는다.
@Component
@RequiredArgsConstructor
public class StockBackfillSeedTargetProvider implements BackfillSeedTargetProvider {

    /** 국내 시장 — 수급 3종 포함 4개 data_table 시딩 대상(KIS J-market TR). */
    private static final Set<Market> DOMESTIC_MARKETS = Set.of(Market.KOSPI, Market.KOSDAQ);

    private final StockRepository stockRepository;

    @Override
    public List<String> activeDomesticSymbols() {
        return stockRepository.findAllActiveTradable().stream()
                .filter(stock -> DOMESTIC_MARKETS.contains(stock.getMarket()))
                .map(Stock::getSymbol)
                .toList();
    }

    @Override
    public List<String> activeOverseasSymbols() {
        return stockRepository.findAllActiveOverseasTradable().stream()
                .map(Stock::getSymbol)
                .toList();
    }
}
