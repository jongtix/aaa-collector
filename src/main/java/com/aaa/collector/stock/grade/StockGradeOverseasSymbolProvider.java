package com.aaa.collector.stock.grade;

import com.aaa.collector.kis.websocket.OverseasSymbolProvider;
import com.aaa.collector.stock.enums.Market;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 등급 기반 해외 WebSocket 구독 대상 tr_key 제공자.
 *
 * <p>미국 A·B 등급 종목 최대 100개를 조회하여 KIS 해외 실시간 tr_key 형식({@code "D" + EXCD + symbol})으로 조립한다.
 */
@Component
@RequiredArgsConstructor
public class StockGradeOverseasSymbolProvider implements OverseasSymbolProvider {

    private static final List<Market> US_MARKETS = List.of(Market.NYSE, Market.NASDAQ, Market.AMEX);
    private static final List<String> GRADE_PRIORITY = List.of("A", "B");
    private static final int MAX_SYMBOLS = 100;

    private final StockGradeRepository stockGradeRepository;

    @Override
    public List<String> getOverseasSubscriptionKeys() {
        return stockGradeRepository
                .findUsSymbolsWithMarketByGradeIn(US_MARKETS, GRADE_PRIORITY)
                .stream()
                .limit(MAX_SYMBOLS)
                .map(row -> "D" + row.market().toDailyPriceExcd() + row.symbol())
                .toList();
    }
}
