package com.aaa.collector.market.session;

import com.aaa.collector.kis.websocket.KisMarketSchedule;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 시장 세션 상태 게이트 (REQ-OBSV-030/033/034/003).
 *
 * <p>두 gauge를 Micrometer에 등록하고 {@code /actuator/prometheus}로 노출한다.
 *
 * <ul>
 *   <li>{@value #MARKET_OPEN_NAME} — 시장 세션 게이트 (장중 1 / 휴장·장외 0)
 *   <li>{@value #GATE_LAST_UPDATE_NAME} — 마지막 성공 갱신 시각 (KST epoch 초, 0=미갱신)
 * </ul>
 *
 * <h3>게이트 판정 의미론</h3>
 *
 * <ul>
 *   <li><b>부팅 후 미설정(boot-unset)</b>: 캘린더 미로드 상태에서는 {@link KisMarketSchedule#isDomesticOpen} 결과만으로
 *       판정한다. 이는 "캘린더 없어서 무조건 0"이 갭 알림을 부당 억제하는 침묵 장애를 방지한다(REQ-OBSV-030).
 *   <li><b>캘린더 로드 후</b>: {@code isDomesticOpen AND opnd_yn=Y} AND 조건으로 판정한다. 오늘 날짜가 캘린더에 없으면
 *       schedule-only 폴백(갭 알림 부당 억제 방지).
 *   <li><b>갱신 실패 시(REQ-OBSV-033)</b>: 직전 캘린더를 유지한다. {@link #updateCalendar(Map)}은 성공 시에만 호출되므로 실패 시
 *       상태가 변경되지 않는다. last-update도 갱신하지 않는다.
 * </ul>
 */
// @MX:ANCHOR: [AUTO] 시장 세션 게이트 — MarketSessionGateRefresher + prometheus 스크랩에서 fan_in >= 3
// @MX:REASON: SPEC-COLLECTOR-OBSV-001 REQ-OBSV-030/033/034 — 갱신(refresher) + 읽기(prometheus) + 억제
// 로직에서
// 사용
// @MX:SPEC: SPEC-COLLECTOR-OBSV-001
@Slf4j
@Component
public class MarketSessionGate {

    /** 시장 세션 게이트 gauge 이름 (장중 1 / 휴장·장외 0). */
    public static final String MARKET_OPEN_NAME = "aaa_collector_market_open";

    /** 마지막 캘린더 갱신 시각 gauge 이름 (KST epoch 초; 0 = 미갱신). */
    public static final String GATE_LAST_UPDATE_NAME =
            "aaa_collector_market_gate_last_update_seconds";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KisMarketSchedule marketSchedule;
    private final Clock clock;

    /**
     * KIS opnd_yn 캘린더 캐시. null = boot-unset(미로드). 로드 후 실패 시 이전 캐시 유지(REQ-OBSV-033). LocalDate →
     * true(개장) / false(휴장).
     */
    private final AtomicReference<Map<LocalDate, Boolean>> calendarRef =
            new AtomicReference<>(null);

    /** 마지막 성공 갱신 시각 (KST epoch 초; 0 = 미갱신). */
    private final AtomicLong lastUpdateEpoch = new AtomicLong(0L);

    /**
     * 생성자. 두 gauge를 registry에 등록한다 — KIS API 호출 없음(REQ-OBSV-030).
     *
     * @param registry Micrometer 레지스트리
     * @param marketSchedule 장중 시간 판정 컴포넌트
     * @param clock 시계 (테스트 고정 시계 주입용)
     */
    public MarketSessionGate(
            MeterRegistry registry, KisMarketSchedule marketSchedule, Clock clock) {
        this.marketSchedule = marketSchedule;
        this.clock = clock;

        Gauge.builder(MARKET_OPEN_NAME, this, MarketSessionGate::computeMarketOpen)
                .description("시장 세션 게이트 — 장중 1, 휴장·장외 0 (REQ-OBSV-030)")
                .register(registry);

        Gauge.builder(GATE_LAST_UPDATE_NAME, lastUpdateEpoch, AtomicLong::doubleValue)
                .description("시장 세션 게이트 마지막 갱신 시각 KST epoch 초; 0=미갱신 (REQ-OBSV-034)")
                .register(registry);
    }

    /**
     * KIS 휴장일 캘린더를 캐시하고 last-update를 현재 시각으로 갱신한다.
     *
     * <p>성공적인 KIS 응답 처리 후에만 호출된다. 실패 시 호출하지 않으면 이전 캐시가 그대로 유지된다(REQ-OBSV-033).
     *
     * @param calendar 날짜 → 개장 여부(true=Y, false=N) 맵
     */
    public void updateCalendar(Map<LocalDate, Boolean> calendar) {
        calendarRef.set(Map.copyOf(calendar));
        lastUpdateEpoch.set(clock.instant().getEpochSecond());
        log.info(
                "시장 세션 게이트 캘린더 갱신 완료 — dates={}, lastUpdate={}",
                calendar.size(),
                lastUpdateEpoch.get());
    }

    /**
     * 현재 시각 기준 시장 개방 여부를 계산한다 (gauge 조회 시 Micrometer가 호출).
     *
     * <p>판정 순서:
     *
     * <ol>
     *   <li>{@code isDomesticOpen(now)} — 스케줄(평일 08:55~15:35 KST) 판정
     *   <li>캘린더 미로드(boot-unset): schedule 결과만 반환 (갭 알림 부당 억제 방지)
     *   <li>캘린더 로드됨: 오늘 날짜가 캘린더에 있으면 AND, 없으면 schedule-only 폴백
     * </ol>
     */
    private double computeMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        boolean scheduleOpen = marketSchedule.isDomesticOpen(now);

        Map<LocalDate, Boolean> calendar = calendarRef.get();
        if (calendar == null) {
            // boot-unset: schedule-only (fail toward NOT wrongly suppressing)
            return scheduleOpen ? 1.0 : 0.0;
        }

        LocalDate today = now.withZoneSameInstant(KST).toLocalDate();
        // 캘린더에 오늘이 없으면 schedule-only 폴백 (stale 캘린더가 갭 알림 부당 억제하지 않도록)
        boolean calendarOpen = calendar.getOrDefault(today, Boolean.TRUE);
        return (scheduleOpen && calendarOpen) ? 1.0 : 0.0;
    }
}
