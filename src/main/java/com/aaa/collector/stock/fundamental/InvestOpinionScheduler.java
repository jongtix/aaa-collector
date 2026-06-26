package com.aaa.collector.stock.fundamental;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 국내주식종목투자의견 수집 스케줄러 (SPEC-COLLECTOR-BATCH-004).
 *
 * <p>평일 18:00 KST({@code 0 0 18 * * MON-FRI}, {@code Asia/Seoul}) — 매일 장 마감 후, 16:00 일봉+수급 멀티키
 * 체인·17:00 BATCH-003 시장지표 묶음 종료 후 슬롯(REQ-BATCH4-002/005). {@code fixedDelay}/{@code fixedRate} 미사용
 * — Virtual Threads 버그 회피(ADR-008, REQ-BATCH4-003).
 *
 * <p>완료 이벤트({@code stream:daily:complete})를 발행하지 않고 자기 완료 로깅만 한다(REQ-BATCH4-012). 수집 중 예외가 발생해도
 * 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-BATCH4-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvestOpinionScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 투자의견 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "domestic-invest-opinion";

    private final InvestOpinionCollectionService collectionService;
    private final BatchMetrics batchMetrics;

    /**
     * 투자의견 수집 배치 진입점 (REQ-BATCH4-002/003/004/005/012).
     *
     * <p>예외 흡수: 수집 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(REQ-BATCH4-004). 예외는 ERROR 로그로 남기고 다음 실행 때 멱등
     * 재시도한다.
     */
    @Scheduled(
            cron = BatchCrons.DOMESTIC_INVEST_OPINION_CRON,
            zone = BatchCrons.DOMESTIC_INVEST_OPINION_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 기존 배치 스케줄러와 동일 패턴
    public void collectInvestOpinion() {
        LocalDate today = LocalDate.now(KST);
        log.info("[invest-opinion] 수집 배치 시작 — {}", today);
        try {
            FundamentalResult result = collectionService.collect(today);
            // REQ-OBSV-020/021: 배치 집계 계측 — fail = attempted-succeeded-skipped.
            long fail =
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL, result.attempted(), result.succeeded(), fail, result.skipped());
            log.info(
                    "[invest-opinion] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[invest-opinion] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
