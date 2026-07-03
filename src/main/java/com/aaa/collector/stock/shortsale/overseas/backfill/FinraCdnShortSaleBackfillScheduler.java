package com.aaa.collector.stock.shortsale.overseas.backfill;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미국 공매도 Daily 과거 백필 전용 스케줄러 (SPEC-COLLECTOR-BACKFILL-008 T6, REQ-BACKFILL-120/-120a/-121/-121a).
 *
 * <p>기존 KIS 종목×테이블 백필 오케스트레이터에 비편입된 독립 전용 스케줄러다(REQ-BACKFILL-120a). {@code
 * DartDisclosureBackfillScheduler} 구조(cron + {@link AtomicBoolean} single-flight 가드)를 미러링한다(OQ8b).
 * [HARD] {@code fixedDelay}/{@code fixedRate} 금지 — Virtual Threads 버그(CLAUDE.md). cron 표현식만 사용한다.
 */
// @MX:NOTE: [AUTO] single-flight = CDN 지연·행오프 시 실행 누적 방어; DART 형제 패턴과 구조 일관성 유지
// @MX:SPEC: SPEC-COLLECTOR-BACKFILL-008
@Slf4j
@Component
@RequiredArgsConstructor
public class FinraCdnShortSaleBackfillScheduler {

    private final FinraCdnShortSaleBackfillOrchestrator orchestrator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * FINRA CDN 백필 cron 진입점 — 기본 05:00 KST 매일.
     *
     * <p>실행 중(single-flight) 재발화는 스킵하고, 오케스트레이터 예외는 흡수해 스케줄러 스레드를 보호한다.
     */
    @Scheduled(
            cron = "${aaa.shortsale-overseas.backfill.cron:0 0 5 * * *}",
            zone = "${aaa.shortsale-overseas.backfill.zone:Asia/Seoul}")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[finra-cdn-backfill-scheduler] 이전 실행 중 — 중복 실행 스킵");
            return;
        }
        try {
            orchestrator.run();
        } catch (Exception e) {
            log.error("[finra-cdn-backfill-scheduler] 백필 실행 오류 — 스케줄러 스레드 보호", e);
        } finally {
            running.set(false);
        }
    }
}
