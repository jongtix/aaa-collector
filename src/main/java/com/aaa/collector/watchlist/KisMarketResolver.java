package com.aaa.collector.watchlist;

import com.aaa.collector.stock.enums.Market;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class KisMarketResolver {

    private KisMarketResolver() {}

    static Market resolve(String fidMrktClsCode, String exchCode) {
        return switch (fidMrktClsCode) {
            case "J" -> Market.KOSPI;
            case "UN" -> Market.KOSDAQ;
            case "U" -> Market.KRX;
            case "N" -> Market.US;
            case "FS" ->
                    switch (exchCode) {
                        case "NAS" -> Market.NASDAQ;
                        case "NYS" -> Market.NYSE;
                        case "AMS" -> Market.AMEX;
                        default -> {
                            log.warn("알 수 없는 해외 거래소 코드: {}", exchCode);
                            yield null;
                        }
                    };
            default -> {
                log.warn("알 수 없는 시장 코드: {}", fidMrktClsCode);
                yield null;
            }
        };
    }
}
