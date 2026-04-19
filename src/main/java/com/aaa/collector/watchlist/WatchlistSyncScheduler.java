package com.aaa.collector.watchlist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 관심종목 동기화 스케줄러. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchlistSyncScheduler {

    private final WatchlistSyncService watchlistSyncService;

    /** 매일 07:30 KST — 장 시작 전 관심종목 동기화. */
    @Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void syncMorning() {
        try {
            log.info("관심종목 동기화 시작 (장 전)");
            watchlistSyncService.sync();
        } catch (Exception e) {
            // 의도된 설계: 예외를 흡수하여 다음 스케줄 실행을 보장한다.
            // @Scheduled 태스크는 예외가 전파되면 해당 실행만 실패하지만,
            // 명시적 로깅을 위해 catch (Exception)을 유지한다.
            log.error("관심종목 동기화 실패 (장 전) — 다음 스케줄까지 대기", e);
        }
    }

    /** 매일 15:45 KST — 장 마감 후 관심종목 동기화. */
    @Scheduled(cron = "0 45 15 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void syncAfternoon() {
        try {
            log.info("관심종목 동기화 시작 (장 후)");
            watchlistSyncService.sync();
        } catch (Exception e) {
            // 의도된 설계: 예외를 흡수하여 다음 스케줄 실행을 보장한다.
            // @Scheduled 태스크는 예외가 전파되면 해당 실행만 실패하지만,
            // 명시적 로깅을 위해 catch (Exception)을 유지한다.
            log.error("관심종목 동기화 실패 (장 후) — 다음 스케줄까지 대기", e);
        }
    }
}
