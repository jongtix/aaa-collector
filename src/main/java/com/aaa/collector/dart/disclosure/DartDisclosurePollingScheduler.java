package com.aaa.collector.dart.disclosure;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DART 공시 폴링 스케줄러 (SPEC-COLLECTOR-DART-001 REQ-DART-010).
 *
 * <p>영업일 22:00 KST 1일 1회 단일 슬롯 — 장 마감 후 당일 공시 포착. [HARD] {@code fixedDelay} 금지 — Virtual Threads
 * 버그(CLAUDE.md).
 *
 * <p>AtomicBoolean 단일 실행 가드 — 중복 실행 방지(REQ-DART, WatchlistSyncScheduler 패턴).
 */
// @MX:ANCHOR: [AUTO] DART 공시 폴링 스케줄러 진입점 — 영업일 22:00 KST 단일 슬롯
// @MX:REASON: [AUTO] SPEC-COLLECTOR-DART-001 REQ-DART-010 — 장 마감 후 당일 공시 포착, 1일 1회 고정 슬롯
@Slf4j
@Component
@RequiredArgsConstructor
public class DartDisclosurePollingScheduler {

    private final DartDisclosurePollingService pollingService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * DART 공시 폴링 cron 진입점 — 영업일 22:00 KST.
     *
     * <p>예외 흡수: 폴링 오류가 스케줄러 스레드를 중단시키지 않는다.
     */
    @Scheduled(cron = "0 0 22 * * MON-FRI", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[dart-polling-scheduler] 이전 실행 중 — 중복 실행 스킵");
            return;
        }
        try {
            pollingService.poll();
        } catch (Exception e) {
            log.error("[dart-polling-scheduler] 폴링 실행 오류 — 스케줄러 스레드 보호", e);
        } finally {
            running.set(false);
        }
    }
}
