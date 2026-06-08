package com.aaa.collector.stock.etf;

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

    private final EtfRepresentativeService etfRepresentativeService;
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
        } catch (Exception e) {
            log.error("ETF representative recalculation failed — will retry next Monday", e);
        } finally {
            running.set(false);
        }
    }
}
