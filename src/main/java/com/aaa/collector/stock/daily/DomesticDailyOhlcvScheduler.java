package com.aaa.collector.stock.daily;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.supply.DomesticSupplyDemandCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 국내 일봉 OHLCV 수집 스케줄러.
 *
 * <p>평일 16:00 KST({@code 0 0 16 * * MON-FRI}, {@code Asia/Seoul}) — 15:30 장 마감 + 데이터 확정 여유 30분.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-BATCH-051).
 *
 * <p>수집/발행 중 예외가 발생해도 흡수하여 스케줄러가 종료되지 않게 한다.
 *
 * <p>인라인 체인(SPEC-COLLECTOR-BATCH-002 REQ-BATCH2-001~004): 일봉 수집 + {@code stream:daily:complete} 발행
 * 완료 후 수급 3종 수집({@link DomesticSupplyDemandCollectionService#collectAll})을 인라인 후속 단계로 호출한다. 신규
 * {@code @Scheduled}/cron을 추가하지 않는다(REQ-BATCH2-004). 일봉 단계와 수급 단계를 분리된 try로 감싸 — 일봉 실패가 수급을 막지
 * 않으며(REQ-BATCH2-002), 수급 예외가 스케줄러 스레드를 종료시키지 않는다(REQ-BATCH2-003).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomesticDailyOhlcvScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 국내 일봉 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "domestic-daily";

    private final DomesticDailyOhlcvCollectionService collectionService;
    private final DailyCompletePublisher publisher;
    private final DomesticSupplyDemandCollectionService supplyDemandService;
    private final BatchMetrics batchMetrics;

    /**
     * 국내 일봉 수집 배치 진입점 (REQ-BATCH-050, REQ-BATCH-051) + 수급 3종 인라인 체인(REQ-BATCH2-001~004).
     *
     * <p>예외 흡수: 일봉 수집·발행, 수급 수집 각 단계의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다. 일봉 단계 예외는 수급 단계를 막지 않는다(단계 간
     * 독립). 예외는 ERROR 로그로 남긴다.
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    public void collectDaily() {
        LocalDate today = LocalDate.now(KST);
        log.info("[domestic-daily] 수집 배치 시작 — {}", today);
        collectDailyOhlcv(today);
        // REQ-BATCH2-001/002: 일봉 발행 후(또는 일봉 실패 시에도) 수급 3종 인라인 트리거 — 단계 간 독립
        collectSupplyDemand(today);
    }

    /** 일봉 수집 + 완료 이벤트 발행. 예외는 흡수(REQ-BATCH-051)하여 후속 수급 단계로 진행한다. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 기존 ETF 스케줄러와 동일 패턴
    private void collectDailyOhlcv(LocalDate today) {
        try {
            CollectionResult result = collectionService.collect(today);
            publisher.publish(result, "domestic");
            // REQ-OBSV-020/021: 배치 집계 계측 — fail은 결과에 명시 카운트가 없어 attempted-succeeded-skipped로 유도.
            long fail =
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL, result.attempted(), result.succeeded(), fail, result.skipped());
            log.info(
                    "[domestic-daily] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[domestic-daily] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }

    /** 수급 3종 인라인 수집. 예외를 체인 내부에서 격리하여 스케줄러 스레드 종료를 방지한다(REQ-BATCH2-003). */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 수급 예외 격리 — 스케줄러 스레드 종료 방지
    private void collectSupplyDemand(LocalDate today) {
        try {
            supplyDemandService.collectAll(today);
        } catch (Exception e) {
            log.error("[supply-demand] 수급 수집 예외 — 스케줄러 스레드 보호, 다음 실행 때 재시도", e);
        }
    }
}
