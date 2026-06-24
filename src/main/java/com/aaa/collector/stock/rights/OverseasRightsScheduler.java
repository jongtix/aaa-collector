package com.aaa.collector.stock.rights;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 해외 현금배당 수집 스케줄러 (SPEC-COLLECTOR-OVERSEAS-ETC-001).
 *
 * <p>평일 17:00 ET({@code 0 0 17 * * MON-FRI}, {@code America/New_York}) — 미국 일봉 16:30 ET 슬롯 이후 일 1회
 * 트리거(REQ-OVE-001, RD-4). {@code America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리하므로 고정 오프셋 수동 분기가
 * 불필요하다.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-OVE-003).
 *
 * <p>수집 중 예외가 발생해도 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-OVE-004). 멱등 저장이라 매일 재수집해도 중복이 증가하지 않는다(RD-5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverseasRightsScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final OverseasRightsCollectionService collectionService;
    private final Clock clock;

    /**
     * 해외 현금배당 수집 배치 진입점 (REQ-OVE-001, -003, -004).
     *
     * <p>예외 흡수: 수집 단계의 예외가 전파되어 스케줄러 스레드가 종료되는 것을 방지한다(다음 실행 때 재시도). 로그 기준일은 ET 거래일이다 — cron 17:00
     * ET(=서버 KST 익일) 발화 시각을 ET zone으로 환산한다. Clock은 테스트에서 {@code Clock.fixed(...)}로 대체된다.
     */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 해외 일봉 스케줄러와 동일 패턴
    public void collectRights() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ET);
        log.info("[overseas-rights] 수집 배치 시작 — {}", today);
        try {
            OverseasRightsCollectionResult result = collectionService.collect();
            log.info(
                    "[overseas-rights] 수집 배치 완료 — attemptedStocks={}, succeededRows={}, skippedStocks={}, skippedNonCashRows={}, skippedValidationRows={}",
                    result.attemptedStocks(),
                    result.succeededRows(),
                    result.skippedStocks(),
                    result.skippedNonCashRows(),
                    result.skippedValidationRows());
        } catch (Exception e) {
            log.error("[overseas-rights] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }
}
