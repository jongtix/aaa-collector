package com.aaa.collector.market.indicator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시장 지표 수집 설정 (SPEC-COLLECTOR-MARKETIND-001, REQ-061).
 *
 * <p>KOREAEXIM_API_KEY / FRED_API_KEY 환경변수 주입, 재시도 상한, cron 설정을 관리한다.
 */
@ConfigurationProperties(prefix = "aaa.market-indicator")
public record MarketIndicatorProperties(
        Usdkrw usdkrw, Backfill backfill, String koreaeximApiKey, String fredApiKey) {

    public record Usdkrw(int emptyRetryMax) {
        public Usdkrw {
            if (emptyRetryMax <= 0) {
                emptyRetryMax = 5;
            }
        }
    }

    public record Backfill(String cron, Usdkrw usdkrw) {
        public record Usdkrw(int staleWeekdayThreshold) {
            public Usdkrw {
                if (staleWeekdayThreshold <= 0) {
                    staleWeekdayThreshold = 7;
                }
            }
        }
    }
}
