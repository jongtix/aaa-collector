package com.aaa.collector.stock.shortsale.overseas;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.schedule.BatchCrons;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미국 공매도 일별 수집 통합 스케줄러 (SPEC-COLLECTOR-SHORTSALE-OVERSEAS-001).
 *
 * <p>평일 10:00 ET({@code 0 0 10 * * MON-FRI}, {@code America/New_York})에 Daily(공매도 거래량)와 Short
 * Interest(잔고)를 순차 폴링한다. FINRA Daily는 T+1 확정이므로, Daily는 크론 발화일의 미국 전영업일({@code
 * previousUsBusinessDay})을 조회한다(REQ-SSO-010, 이슈 #59). Short Interest는 범위 폴링(오늘−40일~오늘)이라 T+1 오프셋이
 * 불필요해 발화일 그대로 사용한다. {@code America/New_York} zone이 서머타임(EDT/EST)을 런타임 자동 처리한다.
 *
 * <p>{@code fixedDelay}/{@code fixedRate} 미사용 — Virtual Threads 버그 회피(ADR-008, REQ-SSO-033).
 *
 * <p>수집 기준일은 ET 거래일({@code LocalDate.ofInstant(clock.instant(), America/New_York)})이다 — cron 10:00
 * ET 발화가 서버 KST로는 익일이므로 KST로 산정하면 거래일이 하루 뒤집힌다. 수집 중 예외는 흡수하여 스케줄러 스레드가 종료되지 않게 한다(REQ-SSO-033).
 */
@Slf4j
@Component
@RequiredArgsConstructor
// @MX:NOTE: [AUTO] 미국 공매도 통합 스케줄러 — Daily→Interest 순차 폴링, ET 거래일 산정, T+1 오프셋, 예외 흡수
public class ShortSaleOverseasScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final ShortSaleOverseasDailyCollectionService dailyService;
    private final ShortSaleOverseasInterestCollectionService interestService;
    private final UsMarketOpenGate usMarketOpenGate;
    private final Clock clock;

    /**
     * 미국 공매도 수집 배치 진입점 (REQ-SSO-010, -014, -033).
     *
     * <p>Daily → Short Interest 순서로 순차 폴링한다. 수집 기준일은 ET 거래일 — KST가 아닌 ET로 산정해야 cron 10:00 ET(=서버
     * KST 익일) 발화에서 거래일이 뒤집히지 않는다. Daily는 FINRA T+1 확정 지연 때문에 전영업일을 조회한다(이슈 #59). 수집 예외는 흡수하여 스케줄러
     * 스레드 종료를 방지한다(다음 실행 때 재시도).
     */
    @Scheduled(cron = BatchCrons.OVERSEAS_SHORTSALE_CRON, zone = BatchCrons.OVERSEAS_SHORTSALE_ZONE)
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // 스케줄러 스레드 종료 방지 — 기존 스케줄러와 동일 패턴
    public void collect() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ET);
        LocalDate dailyTradeDate = previousUsBusinessDay(today);
        log.info(
                "[overseas-shortsale] 수집 배치 시작 — today={}, dailyTradeDate={}",
                today,
                dailyTradeDate);
        try {
            dailyService.collectDaily(dailyTradeDate);
            interestService.collectShortInterest(today);
            log.info("[overseas-shortsale] 수집 배치 완료 — {}", today);
        } catch (Exception e) {
            log.error("[overseas-shortsale] 수집 배치 예외 — 다음 실행 때 재시도", e);
        }
    }

    /**
     * {@code date} 이전 미국 개장일을 하루씩 거슬러 올라가며 찾는다(주말·NYSE 휴장일 자동 스킵).
     *
     * <p>FINRA Daily가 T+1 확정이므로 크론 발화일 당일이 아닌 이 값을 {@code tradeReportDate}로 사용해야 한다(이슈 #59).
     */
    private LocalDate previousUsBusinessDay(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        while (!usMarketOpenGate.isOpenDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
