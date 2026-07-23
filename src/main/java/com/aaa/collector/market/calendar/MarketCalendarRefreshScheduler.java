package com.aaa.collector.market.calendar;

import com.aaa.collector.kis.KisApiBusinessException;
import com.aaa.collector.kis.KisRateLimitException;
import com.aaa.collector.kis.holiday.KisHolidayClient;
import com.aaa.collector.kis.holiday.KisHolidayResponse.HolidayRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * {@code market_calendar} 테이블 신선도 유지 전용 일일 갱신 배치 (SPEC-COLLECTOR-CALENDAR-001, 결정 4).
 *
 * <p>이 스케줄러가 다루는 조회 창(KRX/NYSE 공통 {@code [오늘-3, 오늘+20]})은 오직 테이블 자체를 최신 상태로 유지하는 목적일 뿐이며, 기존
 * 게이트({@code MarketSessionGate}/{@code UsMarketSessionGate})가 판정에 사용하는 유효 범위(TASK-007/008, KRX
 * 오늘-14~오늘+20, NYSE 현재+다음 연도)와는 완전히 별개 개념이다 — 두 개념을 혼동하지 않는다(D10).
 *
 * <h3>KRX (REQ-CAL-010/-012/-013/-014/-015/-016/-017)</h3>
 *
 * <p>평시(마지막 연속 커버일이 어제 이상)에는 {@code today-3} 기준 단일 호출로 24일치를 갱신한다("평시 1콜"). 갭이 존재하면 순차 전체-호출 체이닝(각
 * 호출 최대 반환일+1을 다음 기준일로 사용)으로 메우되, 유한 상한({@value #GAP_HEALING_HORIZON_DAYS}일)을 초과하는 공백은 자동으로 메우지 않고
 * 경보만 발생시킨다(REQ-CAL-014). KIS 응답의 첫 {@code bass_dt}가 요청한 기준일과 다르면(침묵 정규화 등) 그 응답은 반영하지 않고 경보를
 * 발생시킨다(REQ-CAL-015/-016).
 *
 * <h3>NYSE (REQ-CAL-011)</h3>
 *
 * <p>{@link NyseHolidayAlgorithm}으로 순수 계산하여 upsert한다 — 외부 API 호출 없음.
 */
// @MX:TODO: [AUTO] 게이트 캐시(REQ-CAL-018) 즉시 반영 배선 — TASK-007/008에서 연결 예정
// @MX:REASON: 이번 라운드(TASK-005/006)는 테이블 신선도 유지까지만 담당, 게이트 배선은 후속 태스크 범위
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendarRefreshScheduler {

    /** 기존 {@code MarketSessionGateRefresher}와 동일 취지(장 전 갱신)로 재사용하는 cron. */
    static final String REFRESH_CRON = "0 10 8 * * *";

    /** 테이블 신선도 유지용 배치 조회 하한(오늘로부터 며칠 전) — 게이트 범위(TASK-007의 14일)와 별개 값(D10). */
    static final int NORMAL_LOOKBACK_DAYS = 3;

    /** 테이블 신선도 유지용 배치 조회 상한(오늘로부터 며칠 후) — KRX/NYSE 공통. */
    static final int FORWARD_DAYS = 20;

    /** 예외적 갭 치유가 자동으로 메울 수 있는 최대 공백(일) — 초과분은 경보만 발생(REQ-CAL-014). */
    static final int GAP_HEALING_HORIZON_DAYS = 400;

    /** 한 회차에서 KIS 갭 치유 체이닝이 시도할 수 있는 최대 호출 횟수(400일 ÷ 24일/콜 상한, REQ-CAL-014 tripwire). */
    static final int MAX_GAP_CALLS = 17;

    static final String GAP_CAP_EXCEEDED_NAME =
            "aaa_collector_market_calendar_gap_cap_exceeded_total";
    static final String BASS_DT_MISMATCH_NAME =
            "aaa_collector_market_calendar_krx_bass_dt_mismatch_total";
    private static final String CALENDAR_TAG = "calendar";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MarketCalendarRepository marketCalendarRepository;
    private final MarketCalendarService marketCalendarService;
    private final KisHolidayClient kisHolidayClient;
    private final MeterRegistry registry;
    private final Clock clock;

    /** 두 경보 카운터를 0으로 사전 등록한다(드롭 없어도 0 시계열 노출). */
    @PostConstruct
    void initAlertCounters() {
        Counter.builder(GAP_CAP_EXCEEDED_NAME).tag(CALENDAR_TAG, "KRX").register(registry);
        Counter.builder(GAP_CAP_EXCEEDED_NAME).tag(CALENDAR_TAG, "NYSE").register(registry);
        Counter.builder(BASS_DT_MISMATCH_NAME).register(registry);
    }

    /** KRX/NYSE 캘린더를 매일 1회 갱신한다(REQ-CAL-017 — cron 전용, fixedDelay/fixedRate 미사용). */
    @Scheduled(cron = REFRESH_CRON, zone = "Asia/Seoul")
    public void refresh() {
        refreshKrx();
    }

    private LocalDate today() {
        return ZonedDateTime.now(clock).withZoneSameInstant(KST).toLocalDate();
    }

    /**
     * 마지막 연속 커버일을 조회한다 — 갭 치유 상한 창({@value #GAP_HEALING_HORIZON_DAYS}일) 내 최신 날짜를 찾는다. 그 창 안에 행이 전혀
     * 없으면(최초 배포 미시딩 등) {@link Optional#empty()}.
     */
    private Optional<LocalDate> findLastCoveredDate(CalendarCode calendarCode, LocalDate today) {
        return marketCalendarRepository
                .findByCalendarCodeAndCalDateBetween(
                        calendarCode, today.minusDays(GAP_HEALING_HORIZON_DAYS), today)
                .stream()
                .map(MarketCalendar::getCalDate)
                .max(Comparator.naturalOrder());
    }

    private void refreshKrx() {
        LocalDate today = today();
        LocalDate target = today.plusDays(FORWARD_DAYS);
        Optional<LocalDate> lastCovered = findLastCoveredDate(CalendarCode.KRX, today);

        boolean normal = lastCovered.isPresent() && !lastCovered.get().isBefore(today.minusDays(1));
        if (normal) {
            // 평시 — 정확히 1콜(REQ-CAL-012)
            fetchAndUpsertKrx(today.minusDays(NORMAL_LOOKBACK_DAYS));
            return;
        }

        LocalDate capBoundary = today.minusDays(GAP_HEALING_HORIZON_DAYS);
        LocalDate gapStart = lastCovered.map(d -> d.plusDays(1)).orElse(capBoundary);
        boolean capExceeded = lastCovered.isEmpty() || gapStart.isBefore(capBoundary);
        if (capExceeded) {
            alertGapCapExceeded(CalendarCode.KRX);
            gapStart = capBoundary;
        }

        LocalDate baseDate = gapStart;
        int calls = 0;
        while (!baseDate.isAfter(target) && calls < MAX_GAP_CALLS) {
            LocalDate next = fetchAndUpsertKrx(baseDate);
            calls++;
            if (next == null || !next.isAfter(baseDate)) {
                break;
            }
            baseDate = next;
        }
    }

    /**
     * 단일 KRX 조회+검증+upsert를 수행한다.
     *
     * @param baseDate 조회 기준일
     * @return 다음 체이닝 기준일(마지막 응답 날짜+1). 빈 응답이거나 첫 {@code bass_dt} 불일치 시 {@code null}
     */
    private LocalDate fetchAndUpsertKrx(LocalDate baseDate) {
        List<HolidayRow> rows;
        try {
            rows = kisHolidayClient.fetchCalendar(baseDate);
        } catch (RestClientException
                | KisApiBusinessException
                | KisRateLimitException
                | IllegalStateException
                | DateTimeParseException ex) {
            log.warn(
                    "KRX 캘린더 갱신 실패 — baseDate={}, exceptionType={}",
                    baseDate,
                    ex.getClass().getSimpleName(),
                    ex);
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }

        LocalDate firstBassDt;
        try {
            firstBassDt = LocalDate.parse(rows.get(0).bassDt(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            log.warn("KRX 캘린더 첫 행 bass_dt 파싱 실패 — baseDate={}", baseDate);
            return null;
        }
        if (!firstBassDt.equals(baseDate)) {
            // REQ-CAL-016: 첫 bass_dt가 요청 BASS_DT와 다르면(침묵 정규화 등) 반영하지 않고 경보만 발생.
            registry.counter(BASS_DT_MISMATCH_NAME).increment();
            log.warn("KRX 캘린더 갱신 미반영 — 첫 bass_dt 불일치, 요청={}, 응답={}", baseDate, firstBassDt);
            return null;
        }

        LocalDate maxDate = baseDate;
        for (HolidayRow row : rows) {
            LocalDate date;
            try {
                date = LocalDate.parse(row.bassDt(), DATE_FORMAT);
            } catch (DateTimeParseException e) {
                log.warn("KRX 캘린더 행 bass_dt 파싱 실패 — bass_dt={}, 행 무시", row.bassDt());
                continue;
            }
            boolean isOpen = "Y".equals(row.opndYn());
            marketCalendarService.upsert(CalendarCode.KRX, date, isOpen, CalendarSource.KIS_API);
            if (date.isAfter(maxDate)) {
                maxDate = date;
            }
        }
        return maxDate.plusDays(1);
    }

    private void alertGapCapExceeded(CalendarCode calendarCode) {
        registry.counter(GAP_CAP_EXCEEDED_NAME, CALENDAR_TAG, calendarCode.name()).increment();
        log.warn(
                "market_calendar 갱신 — 갭 치유 상한({}일) 초과 또는 미시딩 감지, 경보만 발생 — calendarCode={}",
                GAP_HEALING_HORIZON_DAYS,
                calendarCode);
    }
}
