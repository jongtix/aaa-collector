package com.aaa.collector.news.overseas;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.schedule.BatchCrons;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해외 뉴스 제목 수집 스케줄러 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>미국 장중 10분 간격({@code 0 0/10 9-16 * * MON-FRI}, {@code America/New_York}) 트리거(REQ-OVE-002,
 * RD-4). {@code America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리하므로 고정 오프셋 수동 분기가 불필요하다.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-OVE-003).
 *
 * <p>수집 중 예외가 발생해도 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-OVE-004). 멱등 저장이라 10분마다 재수집해도 중복이 증가하지
 * 않는다(REQ-OVE-042). {@link com.aaa.collector.stock.rights.OverseasRightsScheduler} 패턴 답습.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverseasNewsScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /**
     * BatchMetrics 배치 라벨 (SPEC-OBSV-WATERMARK-001 REQ-WM-013). warm-start=X(MI-01, sub-daily 임계).
     */
    private static final String BATCH_LABEL = "overseas-news";

    private final OverseasNewsTitleCollectionService collectionService;
    private final Clock clock;
    private final BatchMetrics batchMetrics;

    /**
     * 해외 뉴스 제목 수집 배치 진입점 (REQ-OVE-002, -003, -004).
     *
     * <p>예외 흡수: 수집 단계의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(다음 실행 때 재시도). 로그 기준일은 ET 거래일이다 — cron 발화
     * 시각(미국 장중, =서버 KST 익일)을 ET zone으로 환산한다. Clock은 테스트에서 {@code Clock.fixed(...)}로 대체된다. 완료 시
     * {@code overseas-news} 배치 라벨로 실행 신선도를 계측한다(SPEC-OBSV-WATERMARK-001 REQ-WM-013).
     */
    @Scheduled(cron = BatchCrons.OVERSEAS_NEWS_CRON, zone = BatchCrons.OVERSEAS_NEWS_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 해외 일봉/권리 스케줄러와 동일 패턴
    public void collectNews() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ET);
        log.debug("[overseas-news] 수집 배치 시작 — {}", today);
        try {
            OverseasNewsCollectionResult result = collectionService.collect();
            log.info(
                    "[overseas-news] 수집 배치 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
            batchMetrics.recordCompletion(
                    BATCH_LABEL,
                    result.attempted(),
                    result.succeeded(),
                    Math.max(0L, (long) result.attempted() - result.succeeded() - result.skipped()),
                    result.skipped());
        } catch (Exception e) {
            log.error("[overseas-news] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
