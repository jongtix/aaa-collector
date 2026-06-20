package com.aaa.collector.market.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시장 지표 백필 스케줄러 (SPEC-COLLECTOR-MARKETIND-001, REQ-046).
 *
 * <p>기본 02:00 KST 매일. {@code fixedDelay}/{@code fixedRate} 미사용(ADR-008, REQ-046). 예외 흡수 — 스케줄러 스레드
 * 보호.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorBackfillScheduler {

    private final MarketIndicatorBackfillOrchestrator orchestrator;

    /**
     * 시장 지표 백필 진입점 (REQ-046).
     *
     * <p>cron 기본값 {@code "0 0 2 * * *"} (02:00 KST 매일). 설정으로 오버라이드 가능.
     */
    @Scheduled(cron = "${aaa.market-indicator.backfill.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    public void backfillMarketIndicators() {
        log.info("[market-ind-backfill] 백필 스케줄러 시작");
        try {
            orchestrator.seed();
            orchestrator.runBackfill();
            log.info("[market-ind-backfill] 백필 스케줄러 완료");
        } catch (Exception e) {
            log.error("[market-ind-backfill] 백필 스케줄러 예외", e);
        }
    }
}
