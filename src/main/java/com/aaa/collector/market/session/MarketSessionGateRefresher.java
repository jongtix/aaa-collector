package com.aaa.collector.market.session;

import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.MarketCalendar;
import com.aaa.collector.market.calendar.MarketCalendarRepository;
import com.aaa.collector.observability.WatermarkProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시장 세션 게이트 일 1회 cron 갱신 (SPEC-COLLECTOR-CALENDAR-001 TASK-007, REQ-CAL-018/-030/-031/-036).
 *
 * <p>{@code market_calendar}(KRX) 테이블에서 게이트 유효 범위(REQ-CAL-036 — <b>{@code [오늘−N, 오늘+20]}</b>,
 * {@code N}={@link WatermarkProperties#getKrxCalendarLookbackDays()} 기본 14)를 매 회차 재조회하여 {@link
 * MarketSessionGate}의 캐시를 통째로 갈아 끼운다. **이 범위는 {@code MarketCalendarRefreshScheduler}의 테이블 신선도 유지용
 * 배치 조회 창(오늘−3~오늘+20)과는 완전히 별개 개념이다 — 혼동하지 않는다(D10, plan.md §4.3).** 하한 14일은 기존 프로덕션 실측값 {@code
 * WatermarkProperties.krxCalendarLookbackDays} 그대로이며 절대 좁히지 않는다 — 좁히면 {@code CoveredRangeService}의
 * 4~14일 전 커서 조회 경로에서 배포 전후 판정이 달라지는 회귀가 재발한다.
 *
 * <p>이전 리비전(SPEC-COLLECTOR-OBSV-001)은 이 클래스가 직접 KIS {@code CTCA0903R} API를 호출했으나, 이번 SPEC부터 실제 KIS
 * 호출·응답 검증·갭 치유는 {@code MarketCalendarRefreshScheduler}(테이블 신선도 전용)가 전담하고, 이 클래스는 그 결과가 반영된 {@code
 * market_calendar} 테이블을 리포지토리로 조회해 게이트 캐시만 갱신한다.
 *
 * <h3>실패 격리(REQ-OBSV-033)</h3>
 *
 * 리포지토리 조회 실패 시 {@link MarketSessionGate#updateCalendar(Map)}을 호출하지 않아 이전 캐시를 유지한다. last-update도
 * 갱신하지 않아 외부 판정기가 게이트 stale을 독립적으로 감지할 수 있다(REQ-OBSV-046).
 *
 * <h3>cron 선택 (REQ-CAL-017)</h3>
 *
 * {@value #REFRESH_CRON} — 매일 08:10 KST(장 전, 주말 포함). {@code fixedDelay}/{@code fixedRate}
 * 미사용(ADR-008 Virtual Threads 버그).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSessionGateRefresher {

    /**
     * 매일 08:10 KST — 장 전, 주말 포함. 국내 시장 개장(08:55) 전에 당일 개장 여부를 확보한다. 주말 포함으로 last-update 신선도 연속성 보장.
     */
    static final String REFRESH_CRON = "0 10 8 * * *";

    /** 게이트 유효 범위 상한(오늘로부터 며칠 후) — REQ-CAL-036. */
    static final int GATE_FORWARD_DAYS = 20;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MarketCalendarRepository marketCalendarRepository;
    private final MarketSessionGate gate;
    private final Clock clock;
    private final WatermarkProperties watermarkProperties;

    /**
     * {@code market_calendar}(KRX)에서 게이트 유효 범위를 재조회하여 게이트 캐시를 갱신한다.
     *
     * <p>하한은 {@code today − N일}(N = {@code aaa.watermark.krx-calendar-lookback-days}, 기본 14)이고 상한은
     * {@code today + 20일}이다(REQ-CAL-036). 조회 실패는 catch하여 격리한다 — {@link
     * MarketSessionGate#updateCalendar(Map)}을 호출하지 않음으로써 직전 캐시를 유지하고 예외를 전파하지 않는다(REQ-OBSV-033).
     */
    @Scheduled(cron = REFRESH_CRON, zone = "Asia/Seoul")
    public void refresh() {
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(KST).toLocalDate();
        LocalDate lowerBound = today.minusDays(watermarkProperties.getKrxCalendarLookbackDays());
        LocalDate upperBound = today.plusDays(GATE_FORWARD_DAYS);
        try {
            List<MarketCalendar> rows =
                    marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, lowerBound, upperBound);
            Map<LocalDate, Boolean> calendar =
                    rows.stream()
                            .collect(
                                    Collectors.toMap(
                                            MarketCalendar::getCalDate, MarketCalendar::isOpen));
            gate.updateCalendar(calendar);
            log.info(
                    "시장 세션 게이트 갱신 성공 — lowerBound={}, upperBound={}, rows={}",
                    lowerBound,
                    upperBound,
                    rows.size());
        } catch (DataAccessException | IllegalStateException ex) {
            log.warn(
                    "시장 세션 게이트 갱신 실패 — 직전 상태 유지, lowerBound={}, upperBound={}, exceptionType={}",
                    lowerBound,
                    upperBound,
                    ex.getClass().getSimpleName(),
                    ex);
        }
    }

    /**
     * 부팅 시 1회 즉시 refresh를 수행한다(SPEC-OBSV-WATERMARK-001 REQ-WM-007/MA-01) — 재시작 직후에도 KRX expected
     * watermark가 즉시 산출되도록 08:10 cron을 기다리지 않는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh();
    }
}
