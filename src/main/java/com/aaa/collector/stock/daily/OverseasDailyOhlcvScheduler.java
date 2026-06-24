package com.aaa.collector.stock.daily;

import com.aaa.collector.observability.BatchMetrics;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미국(해외) 일봉 OHLCV 수집 스케줄러 (SPEC-COLLECTOR-OVERSEAS-OHLCV-001).
 *
 * <p>평일 16:30 ET({@code 0 30 16 * * MON-FRI}, {@code America/New_York}) — 미국 정규장 마감 16:00 ET + 데이터
 * 확정 여유 30분(국내 '마감+30분' 패턴 동일). {@code America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리하므로 고정
 * 오프셋(KST+13/+14) 수동 분기가 불필요하다(TECHSPEC §178 정합, REQ-OVOH-032).
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-OVOH-020).
 *
 * <p>당일(미확정) 행 가드(REQ-OVOH-015)가 cron 타이밍의 2차 안전망이다 — 마감+30분이 KIS 일봉 확정에 모자라도 ET 당일 행은 적재되지 않는다. 수집
 * 기준일은 ET 거래일({@code LocalDate.now(America/New_York)})이며, 이 값으로 {@code BYMD}와 당일 행 가드가 동작한다.
 *
 * <p>수집/발행 중 예외가 발생해도 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-OVOH-041). 국내 인라인 수급 체인은 없다 — 일봉 수집 + {@code
 * overseas} 발행만 수행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverseasDailyOhlcvScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** 해외 일봉 배치 계측 라벨 (REQ-OBSV-020/021). */
    private static final String BATCH_LABEL = "overseas-daily";

    private final OverseasDailyOhlcvCollectionService collectionService;
    private final DailyCompletePublisher publisher;
    private final Clock clock;
    private final BatchMetrics batchMetrics;

    /**
     * 미국 일봉 수집 배치 진입점 (REQ-OVOH-010, -020, -032, -041).
     *
     * <p>예외 흡수: 수집·발행 단계의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(다음 실행 때 재시도). 수집 기준일은 ET 거래일이다 — KST가 아닌
     * ET로 당일 행 가드가 동작해야 cron 16:30 ET(=서버 KST 익일) 발화에서 가드가 무력화되지 않는다.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 국내 스케줄러와 동일 패턴
    public void collectDaily() {
        // REQ-OVOH-015: ET 거래일 기준 당일 행 가드 — clock.instant()를 ET zone으로 변환한다.
        // Clock은 테스트에서 고정 Clock.fixed(...)로 대체되어 KST/시스템 zone 회귀를 검증한다.
        LocalDate today = LocalDate.ofInstant(clock.instant(), ET);
        log.info("[overseas-daily] 수집 배치 시작 — {}", today);
        try {
            CollectionResult result = collectionService.collect(today);
            publisher.publish(result, "overseas");
            // REQ-OBSV-020/021: 배치 집계 계측 — fail = attempted-succeeded-skipped.
            long fail =
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL, result.attempted(), result.succeeded(), fail, result.skipped());
            log.info(
                    "[overseas-daily] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[overseas-daily] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
