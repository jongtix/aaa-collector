package com.aaa.collector.macro.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 거시경제 지표 백필 cron 스케줄러 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-051).
 *
 * <p>매일 03:00 KST에 {@link MacroIndicatorBackfillOrchestrator#run()}을 발화한다. 예외를 흡수하여 스케줄러 스레드를 보호한다.
 *
 * <p>[HARD] {@code fixedDelay} 금지 — Virtual Threads 버그(CLAUDE.md, ADR-008). cron 표현식만 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroIndicatorBackfillScheduler {

    private final MacroIndicatorBackfillOrchestrator orchestrator;

    /**
     * 거시경제 지표 백필 cron 진입점 (기본 03:00 KST 매일).
     *
     * <p>예외 흡수: 오케스트레이터 오류가 스케줄러 스레드를 중단시키지 않는다.
     */
    @Scheduled(cron = "${aaa.macro.backfill.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void run() {
        try {
            orchestrator.run();
        } catch (Exception e) {
            log.error("[macro-backfill-scheduler] cron 실행 오류 — 스케줄러 스레드 보호", e);
        }
    }
}
