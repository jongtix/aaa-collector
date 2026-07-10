package com.aaa.collector.watchlist;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import com.aaa.collector.stock.grade.GradeClassificationService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 관심종목 동기화 + 등급 분류 스케줄러 (SPEC-COLLECTOR-GRADE-003 Task 5).
 *
 * <ul>
 *   <li>KRX 08:20 KST — 전체 watchlist sync 후 classifyDomestic() (REQ-WLSYNC-111 보존)
 *   <li>US ET 08:50 — 전체 watchlist sync 후 classifyOverseas() (REQ-GRADE-005/M2/M5)
 *   <li>15:45 KST afternoon standalone sync 제거 (REQ-WLSYNC-112 supersede)
 * </ul>
 *
 * <p>슬롯별 독립 AtomicBoolean single-flight 가드(REQ-GRADE-007). classify 예외는 격리 — sync 결과에 영향 없음.
 */
// @MX:NOTE: [AUTO] sync 후 시장별 classify 결합(KRX 08:20 KST / US ET 08:50 '0 50 8' America/New_York).
// WLSYNC-006 REQ-111 보존, REQ-112 supersede(15:45 제거). 슬롯별 독립 가드.
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchlistSyncScheduler {

    /** KRX 관심종목 동기화 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL_KRX = "watchlist-sync-krx";

    /** US 관심종목 동기화 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL_US = "watchlist-sync-us";

    private final WatchlistSyncService watchlistSyncService;
    private final GradeClassificationService gradeClassificationService;
    private final BatchMetrics batchMetrics;

    // REQ-GRADE-007: 슬롯별 독립 single-flight 가드
    private final AtomicBoolean morningRunning = new AtomicBoolean(false);
    private final AtomicBoolean usRunning = new AtomicBoolean(false);

    /**
     * KRX 관심종목 동기화 + 국내 등급 분류 — 매일 08:20 KST.
     *
     * <p>watchlist 전체 sync 후 classifyDomestic() 호출(REQ-WLSYNC-111 보존). sync 예외/classify 예외 모두 격리 —
     * 가드 finally reset으로 다음 실행 보장.
     */
    @Scheduled(cron = BatchCrons.WATCHLIST_SYNC_KRX_CRON, zone = BatchCrons.WATCHLIST_SYNC_KRX_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // sync/classify 예외 격리 — 가드 reset 보장
    public void syncMorning() {
        if (!morningRunning.compareAndSet(false, true)) {
            log.warn("KRX 08:20 sync 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return; // 중복 스킵 시 recordCompletion 미호출
        }
        boolean hasError = false;
        try {
            log.info("KRX 관심종목 동기화 시작 (08:20 KST)");
            watchlistSyncService.sync();
            log.info("KRX 등급 분류 시작 (sync 완료 후)");
            gradeClassificationService.classifyDomestic();
        } catch (Exception e) {
            log.error("KRX sync/classify 실패 — 다음 스케줄까지 대기", e);
            hasError = true;
        } finally {
            morningRunning.set(false);
        }
        // Pattern C: 단일 원자 배치 — sync+classify를 묶어 1회 계측, skip 없음.
        // 예외 시 미-stamp(REQ-XR-009/010): last_load가 실패에도 전진해 실행 신선도 룰이 침묵하는 위반 제거.
        if (!hasError) {
            batchMetrics.recordCompletion(BATCH_LABEL_KRX, 1, 1, 0, 0);
        }
    }

    /**
     * US 관심종목 동기화 + 해외 등급 분류 — 매일 08:50 ET (America/New_York).
     *
     * <p>watchlist 전체 sync(KRX+US 그룹) 후 classifyOverseas() 호출(REQ-GRADE-005/M2/M5). 예외 격리 — 가드
     * finally reset으로 다음 실행 보장.
     */
    @Scheduled(cron = BatchCrons.WATCHLIST_SYNC_US_CRON, zone = BatchCrons.WATCHLIST_SYNC_US_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // sync/classify 예외 격리 — 가드 reset 보장
    public void syncUs() {
        if (!usRunning.compareAndSet(false, true)) {
            log.warn("US ET 08:50 sync 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return; // 중복 스킵 시 recordCompletion 미호출
        }
        boolean hasError = false;
        try {
            log.info("US 관심종목 동기화 시작 (08:50 ET)");
            watchlistSyncService.sync();
            log.info("US 등급 분류 시작 (sync 완료 후)");
            gradeClassificationService.classifyOverseas();
        } catch (Exception e) {
            log.error("US sync/classify 실패 — 다음 스케줄까지 대기", e);
            hasError = true;
        } finally {
            usRunning.set(false);
        }
        // Pattern C: 단일 원자 배치 — sync+classify를 묶어 1회 계측, skip 없음.
        // 예외 시 미-stamp(REQ-XR-009/010): last_load가 실패에도 전진해 실행 신선도 룰이 침묵하는 위반 제거.
        if (!hasError) {
            batchMetrics.recordCompletion(BATCH_LABEL_US, 1, 1, 0, 0);
        }
    }
}
