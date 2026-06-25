package com.aaa.collector.stock.etf;

import com.aaa.collector.observability.BatchMetrics;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ETF 대표 종목 주간 재계산 스케줄러 (REQ-ETFSCHED-001, REQ-ETFSCHED-002).
 *
 * <p>매주 월요일 07:50 KST — 장 시작(09:00) 전 충분한 여유를 두고 실행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtfRepresentativeScheduler {

    /** ETF 대표 종목 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "domestic-etf-representative";

    private final EtfRepresentativeService etfRepresentativeService;
    private final BatchMetrics batchMetrics;
    // package-private: allows same-package tests to access without reflection
    final AtomicBoolean running = new AtomicBoolean(false);

    /** 매주 월요일 07:50 KST (REQ-ETFSCHED-001). */
    @Scheduled(cron = "0 50 7 * * MON", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void recalculateWeekly() {
        if (!running.compareAndSet(false, true)) {
            // REQ-ETFSCHED-002: skip if previous run is still in progress
            log.warn("ETF representative recalculation already running — skipping this trigger");
            return;
        }
        try {
            log.info("ETF representative recalculation started (weekly schedule)");
            etfRepresentativeService.recalculate();
            // Pattern C: 단일 원자 배치(세부 단계 없음) — 성공/실패 이진 계측, skip 없음.
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 1, 0, 0);
        } catch (Exception e) {
            log.error("ETF representative recalculation failed — will retry next Monday", e);
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 0, 1, 0);
        } finally {
            running.set(false);
        }
    }
}
