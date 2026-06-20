package com.aaa.collector.macro;

import com.aaa.collector.macro.ecos.EcosCollectionService;
import com.aaa.collector.macro.fred.FredCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 외부 거시경제 지표(ECOS·FRED) 묶음 스케줄러 (SPEC-COLLECTOR-MACRO-EXT-001 REQ-MACRO-EXT-041).
 *
 * <p>평일 19:00 KST({@code 0 0 19 * * MON-FRI}) — 17:00 KIS 시장지표 배치와 2시간 분리.
 *
 * <p>ECOS → FRED 순차 호출. 종 단위 예외 격리 — 한 종 실패가 다음 종을 막지 않는다. {@code fixedDelay}/{@code fixedRate} 미사용
 * — Virtual Threads 버그 회피(ADR-008, CLAUDE.md).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroExternalScheduler {

    private final EcosCollectionService ecosCollectionService;
    private final FredCollectionService fredCollectionService;

    /**
     * 외부 거시경제 지표 수집 진입점 (REQ-MACRO-EXT-041).
     *
     * <p>예외 흡수: 각 종의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다.
     */
    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Seoul")
    public void collectExternal() {
        log.info("[macro-ext] 외부 거시경제 지표 수집 시작");
        collectEcos();
        collectFred();
        log.info("[macro-ext] 외부 거시경제 지표 수집 완료");
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void collectEcos() {
        try {
            MacroCollectionResult result = ecosCollectionService.collect();
            log.info(
                    "[ecos] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[ecos] 수집 예외 — FRED 계속", e);
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void collectFred() {
        try {
            MacroCollectionResult result = fredCollectionService.collect();
            log.info(
                    "[fred] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[fred] 수집 예외 — 스케줄러 스레드 보호", e);
        }
    }
}
