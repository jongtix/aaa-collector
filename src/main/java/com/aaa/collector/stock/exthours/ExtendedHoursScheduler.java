package com.aaa.collector.stock.exthours;

import com.aaa.collector.observability.BatchMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미국 시간외(Pre/After-Hours) 가격 스냅샷 수집 스케줄러 (SPEC-COLLECTOR-EXTHOURS-001).
 *
 * <p>PRE cron: 10:00 ET(평일) — PRE 세션 마감(09:30 ET) +30분. AFTER cron: 20:30 ET(평일) — POST 세션 마감(20:00
 * ET) +30분. {@code America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리하므로 고정 오프셋 수동 분기가 불필요하다.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtendedHoursScheduler {

    /** BatchMetrics 배치 라벨(SPEC-OBSV-WATERMARK-001 REQ-WM-013) — PRE/AFTER 공유, warm-start=O. */
    private static final String BATCH_LABEL = "extended-hours";

    private final ExtendedHoursCollectionService collectionService;
    private final BatchMetrics batchMetrics;

    /**
     * PRE-Market 가격 스냅샷 수집 (REQ-EXTH-010, REQ-EXTH-050).
     *
     * <p>10:00 ET 평일 — PRE 세션 마감(09:30 ET) +30분. 예외 흡수: 스케줄러 스레드 종료 방지. 완료 시 {@code extended-hours}
     * 배치 라벨로 실행 신선도를 계측한다(SPEC-OBSV-WATERMARK-001 REQ-WM-013).
     */
    @Scheduled(
            cron = "${aaa.extended-hours.pre-cron:0 0 10 * * MON-FRI}",
            zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    public void collectPre() {
        log.info("[extended-hours] PRE 수집 시작");
        try {
            collectionService.collect(Session.PRE);
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 1, 0, 0);
        } catch (Exception e) {
            log.error("[extended-hours] PRE 수집 예외 — 다음 회차 재시도", e);
        }
    }

    /**
     * After-Hours 가격 스냅샷 수집 (REQ-EXTH-010, REQ-EXTH-051).
     *
     * <p>20:30 ET 평일 — POST 세션 마감(20:00 ET) +30분. 예외 흡수: 스케줄러 스레드 종료 방지. 완료 시 {@code
     * extended-hours} 배치 라벨로 실행 신선도를 계측한다(SPEC-OBSV-WATERMARK-001 REQ-WM-013).
     */
    @Scheduled(
            cron = "${aaa.extended-hours.after-cron:0 30 20 * * MON-FRI}",
            zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    public void collectAfter() {
        log.info("[extended-hours] AFTER 수집 시작");
        try {
            collectionService.collect(Session.AFTER);
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 1, 0, 0);
        } catch (Exception e) {
            log.error("[extended-hours] AFTER 수집 예외 — 다음 회차 재시도", e);
        }
    }
}
