package com.aaa.collector.stock.fundamental;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 국내주식재무비율 수집 스케줄러 (SPEC-COLLECTOR-BATCH-004).
 *
 * <p>토요일 08:00 KST({@code 0 0 8 * * SAT}, {@code Asia/Seoul}) — 분기 결산 갱신이라 주1회, 시장 데이터 비의존
 * (REQ-BATCH4-001). {@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008,
 * REQ-BATCH4-003).
 *
 * <p>토큰 갱신 cron({@code KisTokenScheduler} = {@code 0 15 8 * * MON-FRI}, 평일 전용)이 토요일에 동작하지 않으므로, 토요일
 * 재무 배치는 사전발급 토큰이 없다. 토큰은 게이트 호출 경로({@code GuardedKisExecutor}→{@code KisApiExecutor})에 내장된 REST
 * access_token Lazy 발급 fallback({@code getValidToken}→캐시 미스 시 {@code issueOne})에 의존해 확보한다(D-9). 본
 * 스케줄러는 신규 토큰 cron이나 eager 발급을 추가하지 않는다 — WLSYNC-006이 보존한 Lazy 경로를 재사용한다.
 *
 * <p>완료 이벤트({@code stream:daily:complete})를 발행하지 않고 자기 완료 로깅만 한다(REQ-BATCH4-012). 수집 중 예외가 발생해도
 * 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-BATCH4-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinancialRatioScheduler {

    /** 재무비율 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "domestic-financial-ratio";

    private final FinancialRatioCollectionService collectionService;
    private final BatchMetrics batchMetrics;

    /**
     * 재무비율 수집 배치 진입점 (REQ-BATCH4-001/003/004/005/012).
     *
     * <p>예외 흡수: 수집 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(REQ-BATCH4-004). 예외는 ERROR 로그로 남기고 다음 주 실행 때 멱등
     * 재시도한다.
     */
    @Scheduled(
            cron = BatchCrons.DOMESTIC_FINANCIAL_RATIO_CRON,
            zone = BatchCrons.DOMESTIC_FINANCIAL_RATIO_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 기존 배치 스케줄러와 동일 패턴
    public void collectFinancialRatio() {
        log.info("[financial-ratio] 수집 배치 시작 (토요일 주1회)");
        try {
            FundamentalResult result = collectionService.collect();
            // REQ-OBSV-020/021: 배치 집계 계측 — fail = attempted-succeeded-skipped.
            long fail =
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL, result.attempted(), result.succeeded(), fail, result.skipped());
            log.info(
                    "[financial-ratio] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[financial-ratio] 수집 배치 예외 — 다음 주 실행 때 재시도", e);
        }
    }
}
