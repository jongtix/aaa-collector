package com.aaa.collector.dart.corpcode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DART corp_code 매핑 갱신 스케줄러 (SPEC-COLLECTOR-DART-001).
 *
 * <p>매일 07:30 KST에 corpCode.zip을 다운로드하고 상장사 매핑을 INSERT IGNORE로 적재한다. [HARD] {@code fixedDelay} 금지 —
 * Virtual Threads 버그(CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorpCodeUpdateScheduler {

    private final CorpCodeUpdateService corpCodeUpdateService;

    /**
     * corp_code 매핑 갱신 cron 진입점 — 매일 07:30 KST.
     *
     * <p>예외 흡수: 갱신 오류가 스케줄러 스레드를 중단시키지 않는다.
     */
    @Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void update() {
        try {
            corpCodeUpdateService.update();
        } catch (Exception e) {
            log.error("[dart-corpcode-scheduler] 매핑 갱신 오류 — 스케줄러 스레드 보호", e);
        }
    }
}
