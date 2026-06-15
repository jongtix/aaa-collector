package com.aaa.collector.news;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 뉴스 제목 증분 수집 스케줄러 (T9, REQ-BATCH3-002).
 *
 * <p>장중 10분 간격({@code 0 0/10 9-15 * * MON-FRI}, {@code Asia/Seoul}).
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-BATCH3-003). 예외
 * 격리 — 스케줄러 스레드 종료 방지(REQ-BATCH3-004). {@code stream:daily:complete} 미발행(REQ-BATCH3-011).
 *
 * <p>REST isa 키 사용 — WS approval 키와 키가 다르므로 장중 WS와 충돌하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsScheduler {

    private final NewsTitleCollectionService newsTitleCollectionService;

    /**
     * 뉴스 증분 수집 배치 진입점 (REQ-BATCH3-002, REQ-BATCH3-006).
     *
     * <p>예외 흡수: 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(REQ-BATCH3-004).
     */
    @Scheduled(cron = "0 0/10 9-15 * * MON-FRI", zone = "Asia/Seoul")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지
    public void collectNews() {
        log.debug("[news] 뉴스 증분 수집 시작");
        try {
            NewsCollectionResult result = newsTitleCollectionService.collect();
            log.info(
                    "[news] 수집 완료 — attempted={}, succeeded={}, skipped={}",
                    result.attempted(),
                    result.succeeded(),
                    result.skipped());
        } catch (Exception e) {
            log.error("[news] 수집 예외 — 스케줄러 스레드 보호, 다음 실행 때 재시도", e);
        }
    }
}
