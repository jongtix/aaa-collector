package com.aaa.collector.macro;

import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.fred.FredCollectionService;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 외부 거시경제 지표(ECOS·FRED) 묶음 스케줄러 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-041).
 *
 * <p>평일 19:00 KST({@code 0 0 19 * * MON-FRI}) — 17:05 KIS 시장지표 배치와 2시간 분리.
 *
 * <p>ECOS → FRED 순차 호출. 종 단위 예외 격리 — 한 종 실패가 다음 종을 막지 않는다. {@code fixedDelay}/{@code fixedRate} 미사용
 * — Virtual Threads 버그 회피(ADR-008, CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroExternalScheduler {

    /** 거시경제 외부 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "macro-external";

    private final EcosCollectionService ecosCollectionService;
    private final FredCollectionService fredCollectionService;
    private final BatchMetrics batchMetrics;

    /**
     * 외부 거시경제 지표 수집 진입점 (REQ-MACRO-EXT-041).
     *
     * <p>예외 흡수: 각 종의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다.
     *
     * <p>계측 누적 전략: ECOS/FRED 결과를 collectExternal() 수준에서 합산하여 1회 recordCompletion 호출. 예외 발생 종은
     * attempted+=1 처리되고 succeeded/skip에 기여하지 않으므로 fail로 자동 계상된다 (REQ-OBSV-020/021).
     */
    @Scheduled(cron = BatchCrons.MACRO_EXTERNAL_CRON, zone = BatchCrons.MACRO_EXTERNAL_ZONE)
    public void collectExternal() {
        log.info("[macro-ext] 외부 거시경제 지표 수집 시작");
        // ECOS와 FRED 결과를 합산해 1회 recordCompletion 호출
        long attempted = 0;
        long succeeded = 0;
        long skip = 0;

        // ECOS 수집 — null은 예외(attempted+=1, succeeded/skip 미증가 → fail로 자동 계상)
        MacroCollectionResult ecosResult = collectEcos();
        if (ecosResult != null) {
            attempted += ecosResult.attempted();
            succeeded += ecosResult.succeeded();
            skip += ecosResult.skipped();
        } else {
            attempted += 1;
        }

        // FRED 수집 — null은 예외
        MacroCollectionResult fredResult = collectFred();
        if (fredResult != null) {
            attempted += fredResult.attempted();
            succeeded += fredResult.succeeded();
            skip += fredResult.skipped();
        } else {
            attempted += 1;
        }

        // REQ-OBSV-020/021: fail = attempted - succeeded - skip
        long fail = Math.max(0L, attempted - succeeded - skip);
        batchMetrics.recordCompletion(BATCH_LABEL, attempted, succeeded, fail, skip);
        log.info("[macro-ext] 외부 거시경제 지표 수집 완료");
    }

    /**
     * ECOS 수집.
     *
     * @return 수집 결과. 예외 발생 시 null — null은 "빈 결과"가 아니라 "예외 발생"을 의미하며, 호출자(collectExternal)에서
     *     attempted+=1로 계상되어 fail로 자동 집계된다.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MacroCollectionResult collectEcos() {
        try {
            MacroCollectionResult result = ecosCollectionService.collect();
            log.info(
                    "[ecos] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            return result;
        } catch (Exception e) {
            log.error("[ecos] 수집 예외 — FRED 계속", e);
            return null;
        }
    }

    /**
     * FRED 수집.
     *
     * @return 수집 결과. 예외 발생 시 null — collectEcos() 참조.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MacroCollectionResult collectFred() {
        try {
            MacroCollectionResult result = fredCollectionService.collect();
            log.info(
                    "[fred] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            return result;
        } catch (Exception e) {
            log.error("[fred] 수집 예외 — 스케줄러 스레드 보호", e);
            return null;
        }
    }
}
