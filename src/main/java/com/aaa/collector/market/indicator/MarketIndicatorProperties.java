package com.aaa.collector.market.indicator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시장 지표 수집 설정 (SPEC-COLLECTOR-MARKETIND-001, REQ-061).
 *
 * <p>KOREAEXIM_API_KEY / FRED_API_KEY 환경변수 주입, 재시도 상한, cron 설정을 관리한다.
 *
 * <p>{@code usdkrw.emptyRetryMax}는 SPEC-COLLECTOR-MARKETIND-005 TASK-C(라이브 empty-retry 제거)에서 함께
 * 삭제되었다 — 삭제된 {@code aaa.market-indicator.usdkrw.empty-retry-max} 설정 키를 바인딩하는 고아 프로퍼티가 남지 않도록 이 레코드
 * 자체를 제거했다.
 */
@ConfigurationProperties(prefix = "aaa.market-indicator")
public record MarketIndicatorProperties(
        Backfill backfill, String koreaeximApiKey, String fredApiKey) {

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
