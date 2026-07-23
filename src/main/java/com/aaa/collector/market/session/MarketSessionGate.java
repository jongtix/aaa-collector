package com.aaa.collector.market.session;

import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.kis.websocket.KisMarketSchedule;
import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.MarketCalendar;
import com.aaa.collector.market.calendar.MarketCalendarRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
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
public class MarketSessionGate implements MarketOpenGate {

    /** 시장 세션 게이트 gauge 이름 (장중 1 / 휴장·장외 0). */
    public static final String MARKET_OPEN_NAME = "aaa_collector_market_open";

    /** 마지막 캘린더 갱신 시각 gauge 이름 (KST epoch 초; 0 = 미갱신). */
    public static final String GATE_LAST_UPDATE_NAME =
            "aaa_collector_market_gate_last_update_seconds";

    /** 데이터 워터마크 expected 게이지 이름 (SPEC-OBSV-WATERMARK-001 REQ-WM-006/007/008). */
    public static final String EXPECTED_WATERMARK_NAME = "aaa_collector_expected_watermark_seconds";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** KRX 정규장 세션 마감 시각 (REQ-WM-006, MA-02). */
    private static final LocalTime KRX_SESSION_CLOSE = LocalTime.of(15, 30);

    /** expected watermark 후방 탐색 상한 — 캘린더 lookback(N=14)을 상회하는 안전 마진(MI-04). */
    private static final int MAX_LOOKBACK_DAYS = 20;

    private final MeterRegistry registry;
    private final KisMarketSchedule marketSchedule;
    private final Clock clock;
    private final MarketCalendarRepository marketCalendarRepository;

    /**
     * KIS opnd_yn 캘린더 캐시. null = boot-unset(미로드). 로드 후 실패 시 이전 캐시 유지(REQ-OBSV-033). LocalDate →
     * true(개장) / false(휴장).
     */
    private final AtomicReference<Map<LocalDate, Boolean>> calendarRef =
            new AtomicReference<>(null);

    /** 마지막 성공 갱신 시각 (KST epoch 초; 0 = 미갱신). */
    private final AtomicLong lastUpdateEpoch = new AtomicLong(0L);

    /**
     * KRX expected watermark 게이지 핸들. null = 미등록(캘린더 미로드, REQ-WM-008 진성 absent). {@link
     * #updateCalendar}에서 캘린더가 최초로 로드되면 등록된다.
     */
    private final AtomicReference<Gauge> expectedWatermarkGaugeRef = new AtomicReference<>(null);

    /**
     * 생성자. 두 gauge를 registry에 즉시 등록한다 — KIS API 호출 없음(REQ-OBSV-030). {@value
     * #EXPECTED_WATERMARK_NAME}은 캘린더가 로드되기 전까지 진성 absent를 유지해야 하므로({@link #updateCalendar}) 지연
     * 등록한다(REQ-WM-008). {@code registry}는 이 지연 등록/해제를 위해 필드로 보유한다(EI_EXPOSE_REP2 — market.session
     * 패키지 예외목록 등재, SPEC-OBSV-WATERMARK-001).
     *
     * @param registry Micrometer 레지스트리
     * @param marketSchedule 장중 시간 판정 컴포넌트
     * @param clock 시계 (테스트 고정 시계 주입용)
     * @param marketCalendarRepository {@code market_calendar}(KRX) 조회용 리포지토리({@code
     *     isOpenDayStrict} 전용, SPEC-COLLECTOR-CALENDAR-001 TASK-009)
     */
    public MarketSessionGate(
            MeterRegistry registry,
            KisMarketSchedule marketSchedule,
            Clock clock,
            MarketCalendarRepository marketCalendarRepository) {
        this.registry = registry;
        this.marketSchedule = marketSchedule;
        this.clock = clock;
        this.marketCalendarRepository = marketCalendarRepository;

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
        registerExpectedWatermarkIfAbsent();
        log.info(
                "시장 세션 게이트 캘린더 갱신 완료 — dates={}, lastUpdate={}",
                calendar.size(),
                lastUpdateEpoch.get());
    }

    /**
     * 캘린더가 최초로 로드된 시점에 {@value #EXPECTED_WATERMARK_NAME} 게이지를 등록한다(REQ-WM-008).
     *
     * <p>캘린더는 로드 후 실패해도 이전 값을 유지하므로(REQ-OBSV-033) 일단 등록되면 이후 다시 해제되지 않는다.
     */
    private void registerExpectedWatermarkIfAbsent() {
        if (expectedWatermarkGaugeRef.get() != null) {
            return;
        }
        Gauge gauge =
                Gauge.builder(
                                EXPECTED_WATERMARK_NAME,
                                this,
                                MarketSessionGate::computeExpectedWatermarkEpoch)
                        .tags(Tags.of("market", "domestic"))
                        .description(
                                "KRX 세션 마감이 지난 가장 최근 개장일의 KST 자정 epoch 초; 캘린더 미로드 시 게이지 자체가 absent"
                                        + " (REQ-WM-006/008)")
                        .register(registry);
        expectedWatermarkGaugeRef.set(gauge);
    }

    /**
     * KRX expected watermark(세션 마감이 지난 가장 최근 개장일)를 KST 자정 epoch 초로 계산한다(REQ-WM-006, stateless).
     *
     * <p>오늘이 세션 마감(15:30 KST) 이전이면 전날부터, 이후면 오늘부터 역방향으로 개장일을 탐색한다. {@value #MAX_LOOKBACK_DAYS}일 이내
     * 개장일을 찾지 못하면 탐색 시작일을 그대로 반환한다(방어적 상한).
     *
     * <p>이 게이지는 캘린더가 로드된 이후에만 등록되므로({@link #registerExpectedWatermarkIfAbsent}) 호출 시점에 {@link
     * #computeExpectedTradeDate}가 {@code null}을 반환하는 경우는 없다.
     */
    private double computeExpectedWatermarkEpoch() {
        LocalDate candidate = computeExpectedTradeDate();
        return candidate == null ? Double.NaN : candidate.atStartOfDay(KST).toEpochSecond();
    }

    /**
     * KRX expected watermark의 기준 거래일을 계산한다(REQ-WM-006/010 — 커버리지 게이지의 분모 기준일로도 재사용).
     *
     * @return 세션 마감이 지난 가장 최근 개장일. 캘린더 미로드(boot-unset) 상태면 {@code null}(REQ-WM-008 근사)
     */
    public LocalDate computeExpectedTradeDate() {
        if (calendarRef.get() == null) {
            return null;
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(KST);
        LocalDate candidate =
                now.toLocalTime().isBefore(KRX_SESSION_CLOSE)
                        ? now.toLocalDate().minusDays(1)
                        : now.toLocalDate();
        for (int i = 0; i < MAX_LOOKBACK_DAYS && !isOpenDay(candidate); i++) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /**
     * 지정 날짜가 KIS 캘린더 기준 개장일인지 반환한다 (배치 수집 게이트 전용).
     *
     * <p>판정 규칙:
     *
     * <ul>
     *   <li>캘린더 미로드(boot-unset): {@code true} 반환 — 수집 억제 방향 오류 방지
     *   <li>캘린더에 해당 날짜 없음: {@code true} 반환 — stale 캘린더가 수집을 막지 않도록
     *   <li>캘린더에 해당 날짜 있음: {@code opnd_yn} 값 반환
     * </ul>
     *
     * <p><b>정책 비대칭 주의</b>: {@link #computeMarketOpen()}는 gauge 전용(schedule-only fallback)이고 이 메서드는
     * 배치용(fail-open). 두 메서드는 목적이 다르므로 정책이 다름 — 의도된 설계.
     *
     * <p>동시성: {@code calendarRef.get()}의 happens-before 보장으로 {@code Map} 가시성 OK. {@link
     * #updateCalendar}가 {@code Map.copyOf()}로 불변 복사본을 전달하므로 read-only 사용 안전.
     *
     * @param date 판정할 날짜 (KST)
     * @return 개장일이면 {@code true}, 휴장일이면 {@code false}
     */
    @Override
    public boolean isOpenDay(LocalDate date) {
        Map<LocalDate, Boolean> calendar = calendarRef.get();
        return calendar == null || calendar.getOrDefault(date, Boolean.TRUE);
    }

    /**
     * {@code market_calendar}(KRX)를 게이트 캐시가 아니라 직접 조회한다(SPEC-COLLECTOR-CALENDAR-001
     * REQ-CAL-032/-038, TASK-009). 게이트 캐시 범위(오늘−14~오늘+20) 밖의 과거·미래 날짜도 조회 가능하며, 행이 없으면 fail-open이
     * 아니라 "모름"({@link Optional#empty()})을 반환한다 — {@link #isOpenDay(LocalDate)}와는 계약이 다르다.
     *
     * @param date 판정할 날짜(제한 없음)
     * @return 행이 있으면 {@code Optional.of(is_open)}, 없으면 {@link Optional#empty()}
     */
    @Override
    public Optional<Boolean> isOpenDayStrict(LocalDate date) {
        return marketCalendarRepository
                .findByCalendarCodeAndCalDate(CalendarCode.KRX, date)
                .map(MarketCalendar::isOpen);
    }

    /**
     * 현재 시각 기준 국내 장이 열려 있는지 반환한다 (REQ-WSREC-020/021).
     *
     * <p>판정 순서:
     *
     * <ol>
     *   <li>{@code isDomesticOpen(now)} — 스케줄(평일 08:55~15:35 KST) 판정
     *   <li>캘린더 미로드(boot-unset): schedule 결과만 반환 — fail-open(REQ-WSREC-030)
     *   <li>캘린더 로드됨: 오늘 날짜가 캘린더에 있으면 AND, 없으면 schedule-only 폴백
     * </ol>
     *
     * <p>boot-unset 시 평일 공휴일에 {@code true}를 반환할 수 있으나 빈 구독으로 무해하며 수용된 트레이드오프다(REQ-WSREC-030).
     */
    @Override
    public boolean isMarketOpenNow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        boolean scheduleOpen = marketSchedule.isDomesticOpen(now);

        Map<LocalDate, Boolean> calendar = calendarRef.get();
        if (calendar == null) {
            // boot-unset: schedule-only fail-open (갭 알림 부당 억제 방지, REQ-WSREC-030)
            return scheduleOpen;
        }

        // 캘린더에 오늘이 없으면 schedule-only 폴백 (stale 캘린더가 수집 억제하지 않도록)
        LocalDate today = now.withZoneSameInstant(KST).toLocalDate();
        boolean calendarOpen = calendar.getOrDefault(today, Boolean.TRUE);
        return scheduleOpen && calendarOpen;
    }

    /**
     * 현재 시각 기준 시장 개방 여부를 계산한다 (gauge 조회 시 Micrometer가 호출).
     *
     * <p>{@link #isMarketOpenNow()}에 위임하여 단일 판정 로직을 보장한다(REQ-WSREC-021).
     */
    private double computeMarketOpen() {
        return isMarketOpenNow() ? 1.0 : 0.0;
    }
}
