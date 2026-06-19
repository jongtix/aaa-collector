package com.aaa.collector.market.session;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.kis.holiday.KisHolidayResponse.HolidayRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 시장 세션 게이트 일 1회 cron 갱신 (REQ-OBSV-031/032/033).
 *
 * <p>KIS {@code CTCA0903R} 휴장일 API를 일 1회 조회하여 {@link MarketSessionGate}의 캘린더 캐시를 갱신한다. KIS가 1일 1회
 * 호출을 권고하므로 단일 스케줄링으로 운영한다(§1.6).
 *
 * <h3>실패 격리(REQ-OBSV-033)</h3>
 *
 * API 호출·응답 파싱 실패 시 {@link MarketSessionGate#updateCalendar(Map)}을 호출하지 않아 이전 캐시를 유지한다.
 * last-update도 갱신하지 않아 외부 판정기가 게이트 stale을 독립적으로 감지할 수 있다(REQ-OBSV-046).
 *
 * <h3>cron 선택 (REQ-OBSV-032)</h3>
 *
 * {@value #REFRESH_CRON} — 매일 08:10 KST(장 전, 주말 포함). 장 전에 당일 개장 여부를 확보하여 08:55 개장 직후부터 정확한 게이트를
 * 제공한다. 주말에도 실행하여 last-update 신선도가 끊기지 않게 한다. {@code fixedDelay}/{@code fixedRate} 미사용(ADR-008
 * Virtual Threads 버그).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSessionGateRefresher {

    /**
     * 매일 08:10 KST — 장 전, 주말 포함. 국내 시장 개장(08:55) 전에 당일 개장 여부를 확보한다. 주말 포함으로 last-update 신선도 연속성 보장.
     */
    static final String REFRESH_CRON = "0 10 8 * * *";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final KisHolidayClient kisHolidayClient;
    private final MarketSessionGate gate;
    private final Clock clock;

    /**
     * KIS 휴장일 캘린더를 조회하여 게이트를 갱신한다.
     *
     * <p>API 호출·파싱의 모든 실패는 catch하여 격리한다 — {@link MarketSessionGate#updateCalendar(Map)}을 호출하지 않음으로써
     * 직전 캐시를 유지하고 예외를 전파하지 않는다(REQ-OBSV-033).
     */
    @Scheduled(cron = REFRESH_CRON, zone = "Asia/Seoul")
    public void refresh() {
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(KST).toLocalDate();
        try {
            List<HolidayRow> rows = kisHolidayClient.fetchCalendar(today);
            Map<LocalDate, Boolean> calendar = buildCalendar(rows);
            gate.updateCalendar(calendar);
            log.info("시장 세션 게이트 갱신 성공 — date={}, rows={}", today, rows.size());
        } catch (RestClientException
                | KisApiBusinessException
                | KisRateLimitException
                | IllegalStateException
                | DateTimeParseException ex) {
            log.warn(
                    "시장 세션 게이트 갱신 실패 — 직전 상태 유지, date={}, exceptionType={}",
                    today,
                    ex.getClass().getSimpleName(),
                    ex);
        }
    }

    /**
     * HolidayRow 목록을 날짜→개장여부 맵으로 변환한다.
     *
     * <p>{@code bass_dt} 파싱 실패 행은 무시한다(DateTimeParseException 개별 행 수준 스킵). bass_dt 파싱 실패가 예외로 전파되면
     * {@link #refresh()}의 catch 블록이 처리한다.
     */
    private static Map<LocalDate, Boolean> buildCalendar(List<HolidayRow> rows) {
        Map<LocalDate, Boolean> calendar = new ConcurrentHashMap<>();
        for (HolidayRow row : rows) {
            try {
                LocalDate date = LocalDate.parse(row.bassDt(), DATE_FORMAT);
                calendar.put(date, "Y".equals(row.opndYn()));
            } catch (DateTimeParseException e) {
                log.warn("휴장일 날짜 파싱 실패 — bass_dt={}, 행 무시", row.bassDt());
            }
        }
        return calendar;
    }
}
