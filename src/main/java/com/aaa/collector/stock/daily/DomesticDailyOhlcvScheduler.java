package com.aaa.collector.stock.daily;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DomesticDailyOhlcvScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DomesticDailyOhlcvCollectionService collectionService;
    private final DailyCompletePublisher publisher;

    /**
     * 국내 일봉 수집 배치 진입점 (REQ-BATCH-050, REQ-BATCH-051).
     *
     * <p>예외 흡수: 수집 또는 발행 중 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다. 예외는 ERROR 로그로 남긴다.
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 기존 ETF 스케줄러와 동일 패턴
    public void collectDaily() {
        log.info("[domestic-daily] 수집 배치 시작 — {}", LocalDate.now(KST));
        try {
            CollectionResult result = collectionService.collect(LocalDate.now(KST));
            publisher.publish(result);
            log.info(
                    "[domestic-daily] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[domestic-daily] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
