package com.aaa.collector.dart.backfill;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DART 공시 백필 스케줄러 (SPEC-COLLECTOR-DART-001 REQ-DART-021).
 *
 * <p>매일 04:30 KST에 {@link DartDisclosureBackfillOrchestrator#run()}을 발화한다. [HARD] {@code
 * fixedDelay} 금지 — Virtual Threads 버그(CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DartDisclosureBackfillScheduler {

    /**
     * BatchMetrics 배치 라벨(SPEC-OBSV-WATERMARK-001 REQ-WM-013) — 기존 {@code dart-disclosure}와 구분,
     * warm-start=O.
     */
    private static final String BATCH_LABEL = "dart-backfill";

    private final DartDisclosureBackfillOrchestrator orchestrator;
    private final BatchMetrics batchMetrics;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * DART 공시 백필 cron 진입점 — 매일 04:30 KST.
     *
     * <p>예외 흡수: 오케스트레이터 오류가 스케줄러 스레드를 중단시키지 않는다. 완료 시 {@code dart-backfill} 배치 라벨로 실행 신선도를 계측한다.
     */
    @Scheduled(cron = BatchCrons.DART_BACKFILL_CRON, zone = BatchCrons.DART_BACKFILL_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[dart-backfill-scheduler] 이전 실행 중 — 중복 실행 스킵");
            return;
        }
        try {
            orchestrator.run();
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 1, 0, 0);
        } catch (Exception e) {
            log.error("[dart-backfill-scheduler] 백필 실행 오류 — 스케줄러 스레드 보호", e);
        } finally {
            running.set(false);
        }
    }
}
