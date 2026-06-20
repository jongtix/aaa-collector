package com.aaa.collector.stock.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 백필 cron 스케줄러 (SPEC-COLLECTOR-BACKFILL-001 T7).
 *
 * <p>매일 02:00 KST에 {@link BackfillOrchestrator#run()}을 발화한다. 예외를 흡수하여 스케줄러 스레드를 보호한다 — 오케스트레이터 예외가
 * 스케줄러 스레드를 종료시키지 않는다.
 *
 * <p>패키지 위치: {@code stock.backfill} — {@link BackfillOrchestrator}와 동일 패키지.
 *
 * <p>[HARD] {@code fixedDelay} 금지 — Virtual Threads 버그(CLAUDE.md, SPEC-COLLECTOR-BACKFILL-001
 * AC-7.2). cron 표현식만 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackfillScheduler {

    private final BackfillOrchestrator orchestrator;

    /**
     * 백필 cron 진입점 (기본 02:00 KST 매일).
     *
     * <p>예외 흡수: 오케스트레이터 오류가 스케줄러 스레드를 중단시키지 않는다. 오류는 ERROR 레벨로 기록한다.
     */
    @Scheduled(cron = "${aaa.backfill.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 보호 — 모든 예외 흡수 의도적
    public void run() {
        try {
            orchestrator.run();
        } catch (Exception e) {
            log.error("[backfill-scheduler] cron 실행 오류 — 스케줄러 스레드 보호", e);
        }
    }
}
