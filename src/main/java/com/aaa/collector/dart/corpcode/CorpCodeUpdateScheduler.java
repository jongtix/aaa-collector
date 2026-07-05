package com.aaa.collector.dart.corpcode;

import com.aaa.collector.observability.BatchMetrics;
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

    /**
     * BatchMetrics 배치 라벨(SPEC-OBSV-WATERMARK-001 REQ-WM-013). warm-start는 {@code
     * corp_code_mapping}에 per-run timestamp 컬럼 존재 여부 확인 후 조건부(REQ-WM-014, MI-06).
     */
    private static final String BATCH_LABEL = "corp-code";

    private final CorpCodeUpdateService corpCodeUpdateService;
    private final BatchMetrics batchMetrics;

    /**
     * corp_code 매핑 갱신 cron 진입점 — 매일 07:30 KST.
     *
     * <p>예외 흡수: 갱신 오류가 스케줄러 스레드를 중단시키지 않는다. 완료 시 {@code corp-code} 배치 라벨로 실행 신선도를 계측한다.
     */
    @Scheduled(cron = "0 30 7 * * *", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void update() {
        try {
            corpCodeUpdateService.update();
            batchMetrics.recordCompletion(BATCH_LABEL, 1, 1, 0, 0);
        } catch (Exception e) {
            log.error("[dart-corpcode-scheduler] 매핑 갱신 오류 — 스케줄러 스레드 보호", e);
        }
    }
}
