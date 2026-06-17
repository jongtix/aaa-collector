package com.aaa.collector.watchlist;

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

    private final WatchlistSyncService watchlistSyncService;
    private final GradeClassificationService gradeClassificationService;

    // REQ-GRADE-007: 슬롯별 독립 single-flight 가드
    private final AtomicBoolean morningRunning = new AtomicBoolean(false);
    private final AtomicBoolean usRunning = new AtomicBoolean(false);

    /**
     * KRX 관심종목 동기화 + 국내 등급 분류 — 매일 08:20 KST.
     *
     * <p>watchlist 전체 sync 후 classifyDomestic() 호출(REQ-WLSYNC-111 보존). sync 예외/classify 예외 모두 격리 —
     * 가드 finally reset으로 다음 실행 보장.
     */
    @Scheduled(cron = "0 20 8 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // sync/classify 예외 격리 — 가드 reset 보장
    public void syncMorning() {
        if (!morningRunning.compareAndSet(false, true)) {
            log.warn("KRX 08:20 sync 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return;
        }
        try {
            log.info("KRX 관심종목 동기화 시작 (08:20 KST)");
            watchlistSyncService.sync();
            log.info("KRX 등급 분류 시작 (sync 완료 후)");
            gradeClassificationService.classifyDomestic();
        } catch (Exception e) {
            log.error("KRX sync/classify 실패 — 다음 스케줄까지 대기", e);
        } finally {
            morningRunning.set(false);
        }
    }

    /**
     * US 관심종목 동기화 + 해외 등급 분류 — 매일 08:50 ET (America/New_York).
     *
     * <p>watchlist 전체 sync(KRX+US 그룹) 후 classifyOverseas() 호출(REQ-GRADE-005/M2/M5). 예외 격리 — 가드
     * finally reset으로 다음 실행 보장.
     */
    @Scheduled(cron = "0 50 8 * * *", zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // sync/classify 예외 격리 — 가드 reset 보장
    public void syncUs() {
        if (!usRunning.compareAndSet(false, true)) {
            log.warn("US ET 08:50 sync 이전 실행 중 — 중복 실행 스킵(REQ-GRADE-007)");
            return;
        }
        try {
            log.info("US 관심종목 동기화 시작 (08:50 ET)");
            watchlistSyncService.sync();
            log.info("US 등급 분류 시작 (sync 완료 후)");
            gradeClassificationService.classifyOverseas();
        } catch (Exception e) {
            log.error("US sync/classify 실패 — 다음 스케줄까지 대기", e);
        } finally {
            usRunning.set(false);
        }
    }
}
