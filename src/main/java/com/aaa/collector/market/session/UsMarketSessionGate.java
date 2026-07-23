package com.aaa.collector.market.session;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.websocket.KisMarketSchedule;
import com.aaa.collector.market.calendar.NyseHolidayAlgorithm;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 미국(NYSE/NASDAQ) 시장 세션 게이트 (SPEC-COLLECTOR-USMKT-001).
 *
 * <p>두 게이지를 Micrometer에 등록하고 {@code /actuator/prometheus}로 노출한다(REQ-010/011).
 *
 * <ul>
 *   <li>{@value #US_MARKET_OPEN_NAME} — 미국 장중 여부 (1 = 장중, 0 = 장외·휴장)
 *   <li>{@value #US_MARKET_HOLIDAY_COUNT_NAME} — 로드된 NYSE 휴장일 수
 * </ul>
 *
 * <h3>휴장일 알고리즘</h3>
 *
 * <p>NYSE 표준 10개 휴장일을 Meeus/Jones/Butcher 등 결정론적 알고리즘으로 계산한다(REQ-001~005). 외부 API 호출 없음. 토요일 → 직전
 * 금요일, 일요일 → 다음 월요일로 준수일(observed)을 산출한다(REQ-004/005).
 *
 * <h3>fail-open 정책</h3>
 *
 * <p>{@link #init()} 미완료(또는 실패) 시 {@link #observedHolidays}는 빈 Set이며, {@link
 * #isOpenDay(LocalDate)}은 {@code true}를 반환한다(REQ-007). 수집 억제 방향 오류(false negative) 방지.
 */
// @MX:ANCHOR: [AUTO] 미국 시장 세션 게이트 — 해외 배치 3종 + WS 복구 스케줄러에서 fan_in >= 3
// @MX:REASON: SPEC-COLLECTOR-USMKT-001 REQ-006~011 — 해외일봉·공매도·배당·WS복구 게이트 수렴 진입점
// @MX:SPEC: SPEC-COLLECTOR-USMKT-001
@Slf4j
@Component
public class UsMarketSessionGate implements UsMarketOpenGate {

    /** 미국 시장 장중 게이지 이름 (장중 1 / 장외·휴장 0). */
    public static final String US_MARKET_OPEN_NAME = "aaa_collector_us_market_open";

    /** 로드된 NYSE 휴장일 수 게이지 이름. */
    public static final String US_MARKET_HOLIDAY_COUNT_NAME =
            "aaa_collector_us_market_gate_holiday_count";

    /** 데이터 워터마크 expected 게이지 이름 (SPEC-OBSV-WATERMARK-001 REQ-WM-006/009). */
    public static final String EXPECTED_WATERMARK_NAME = "aaa_collector_expected_watermark_seconds";

    /**
     * US 일봉 전용 후퇴 기대 게이지 이름 (SPEC-OBSV-WATERMARK-002 REQ-WM2-001). {@value
     * #EXPECTED_WATERMARK_NAME}과 별도 메트릭명 — 같은 이름에 라벨만 추가하면 {@code on(market) group_right} 조인의
     * "one"측 유일성이 깨져 다른 해외 신선도 룰이 many-to-one 위반으로 회귀한다(SPEC §3.1). {@code market="overseas"} 단일
     * 시계열만 노출(국내 variant 없음).
     */
    public static final String EXPECTED_WATERMARK_DAILY_NAME =
            "aaa_collector_expected_watermark_daily_seconds";

    /** US expected watermark 등록/노출 임계 — holiday_count가 이 값 미만이면 게이트 init 미완료로 간주(REQ-WM-009). */
    private static final int HOLIDAY_COUNT_READY_THRESHOLD = 20;

    /** expected watermark 후방 탐색 상한(REQ-WM-006). */
    private static final int MAX_LOOKBACK_DAYS = 20;

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** US 정규장 세션 마감 시각 (REQ-WM-006, MA-02). */
    private static final LocalTime US_SESSION_CLOSE = LocalTime.of(16, 0);

    /** 반일(조기폐장) 시각 (REQ-WM-030, MA-05). */
    private static final LocalTime EARLY_CLOSE_TIME = LocalTime.of(13, 0);

    private final MeterRegistry registry;
    private final KisMarketSchedule marketSchedule;
    private final Clock clock;

    /** 오버라이드 날짜 목록 — 불변 복사본으로 보관 (EI_EXPOSE_REP2 방지). */
    private final List<LocalDate> extraHolidays;

    /** 반일(조기폐장) 날짜 목록 — 불변 복사본으로 보관(REQ-WM-030, EI_EXPOSE_REP2 방지). */
    private final Set<LocalDate> earlyCloseDays;

    /**
     * 알고리즘 계산 + 오버라이드를 합산한 불변 NYSE 휴장일 Set.
     *
     * <p>초기값 {@code Set.of()}(빈 Set)은 fail-open 신호(REQ-007). {@link #init()} 완료 후 20+ 날짜를 포함한다.
     * {@link AtomicReference}로 단일 쓰기(init) → 다중 읽기(Gauge 스크랩·isOpenDay) happens-before 보장.
     */
    private final AtomicReference<Set<LocalDate>> observedHolidaysRef =
            new AtomicReference<>(Set.of());

    /**
     * US expected watermark 게이지 핸들. null = 미등록(holiday_count 미준비, REQ-WM-009 진성 absent). {@link
     * #syncExpectedWatermarkRegistration()}이 {@value #HOLIDAY_COUNT_READY_THRESHOLD} 도달 여부에 따라
     * 등록/해제한다.
     */
    private final AtomicReference<Gauge> expectedWatermarkGaugeRef = new AtomicReference<>(null);

    /**
     * US 일봉 전용 기대 게이지 핸들(SPEC-OBSV-WATERMARK-002). null = 미등록(holiday_count 미준비, REQ-WM2-002 진성
     * absent). {@link #syncExpectedWatermarkRegistration()}이 {@value
     * #HOLIDAY_COUNT_READY_THRESHOLD} 도달 여부에 따라 {@link #expectedWatermarkGaugeRef}와 동일 게이트로
     * 등록/해제한다.
     */
    private final AtomicReference<Gauge> expectedWatermarkDailyGaugeRef =
            new AtomicReference<>(null);

    /**
     * 생성자. 두 게이지를 registry에 등록한다 — API 호출 없음(REQ-010). {@value #EXPECTED_WATERMARK_NAME}은
     * holiday_count가 준비되기 전까지 진성 absent를 유지해야 하므로({@link #init()}) 지연 등록한다(REQ-WM-009). {@code
     * registry}는 이 지연 등록/해제를 위해 필드로 보유한다(EI_EXPOSE_REP2 — market.session 패키지 예외목록 등재,
     * SPEC-OBSV-WATERMARK-001).
     *
     * @param registry Micrometer 레지스트리
     * @param marketSchedule ET 장 시간 판정 컴포넌트
     * @param clock 시계 (테스트 고정 시계 주입용)
     * @param properties 오버라이드 설정
     */
    public UsMarketSessionGate(
            MeterRegistry registry,
            KisMarketSchedule marketSchedule,
            Clock clock,
            UsMarketProperties properties) {
        this.registry = registry;
        this.marketSchedule = marketSchedule;
        this.clock = clock;
        // 불변 복사본으로 보관 — EI_EXPOSE_REP2 방지 (SpotBugs)
        this.extraHolidays = List.copyOf(properties.getExtraHolidays());
        this.earlyCloseDays = Set.copyOf(properties.getEarlyCloseDays());

        Gauge.builder(US_MARKET_OPEN_NAME, this, UsMarketSessionGate::computeMarketOpen)
                .description("미국 시장 게이트 — 장중 1, 장외·휴장 0 (REQ-USMKT-008)")
                .register(registry);

        Gauge.builder(US_MARKET_HOLIDAY_COUNT_NAME, this, g -> g.observedHolidaysRef.get().size())
                .description("미국 시장 게이트 NYSE 휴장일 수 (REQ-USMKT-010)")
                .register(registry);
    }

    /**
     * 현재+익년도 NYSE 휴장일을 계산하고 오버라이드를 합산하여 불변 Set으로 저장한다(REQ-009).
     *
     * <p>package-private: 단위 테스트에서 직접 호출 가능.
     */
    @PostConstruct
    void init() {
        int year = ZonedDateTime.now(clock).withZoneSameInstant(NEW_YORK).getYear();
        Set<LocalDate> holidays = new HashSet<>();
        holidays.addAll(computeObservedHolidays(year));
        holidays.addAll(computeObservedHolidays(year + 1));
        holidays.addAll(extraHolidays);
        observedHolidaysRef.set(Set.copyOf(holidays));
        syncExpectedWatermarkRegistration();
        log.info(
                "[us-market-gate] NYSE 휴장일 Set 초기화 완료 — count={}, year={}/{}",
                observedHolidaysRef.get().size(),
                year,
                year + 1);
    }

    /**
     * holiday_count가 {@value #HOLIDAY_COUNT_READY_THRESHOLD}에 도달했는지에 따라 {@value
     * #EXPECTED_WATERMARK_NAME}·{@value #EXPECTED_WATERMARK_DAILY_NAME} 게이지를 등록하거나 해제한다(REQ-WM-009,
     * REQ-WM2-001/002). 신규 일봉 게이지는 기존 게이지와 동일 ready 게이트에 편승한다(SPEC §3.1).
     */
    private void syncExpectedWatermarkRegistration() {
        boolean ready = observedHolidaysRef.get().size() >= HOLIDAY_COUNT_READY_THRESHOLD;
        Gauge existing = expectedWatermarkGaugeRef.get();
        if (ready) {
            if (existing == null) {
                Gauge gauge =
                        Gauge.builder(
                                        EXPECTED_WATERMARK_NAME,
                                        this,
                                        UsMarketSessionGate::computeExpectedWatermarkEpoch)
                                .tags(Tags.of("market", "overseas"))
                                .description(
                                        "US 세션 마감이 지난 가장 최근 개장일의 KST 자정 epoch 초; holiday_count 미준비 시 게이지"
                                                + " 자체가 absent (REQ-WM-006/009)")
                                .register(registry);
                expectedWatermarkGaugeRef.set(gauge);
            }
        } else if (existing != null) {
            registry.remove(existing);
            expectedWatermarkGaugeRef.set(null);
        }

        Gauge existingDaily = expectedWatermarkDailyGaugeRef.get();
        if (ready) {
            if (existingDaily == null) {
                Gauge dailyGauge =
                        Gauge.builder(
                                        EXPECTED_WATERMARK_DAILY_NAME,
                                        this,
                                        UsMarketSessionGate::computeExpectedWatermarkDailyEpoch)
                                .tags(Tags.of("market", "overseas"))
                                .description(
                                        "US 일봉 전용 후퇴 기대 — 세션 마감이 지난 가장 최근 개장일의 직전 개장일 KST 자정 epoch 초;"
                                                + " holiday_count 미준비 시 게이지 자체가 absent (REQ-WM2-001/002)")
                                .register(registry);
                expectedWatermarkDailyGaugeRef.set(dailyGauge);
            }
        } else if (existingDaily != null) {
            registry.remove(existingDaily);
            expectedWatermarkDailyGaugeRef.set(null);
        }
    }

    /**
     * 지정 날짜가 NYSE 기준 개장일인지 반환한다 (배치 수집 게이트, REQ-006).
     *
     * <p>판정 규칙:
     *
     * <ul>
     *   <li>휴장일 Set 미초기화(빈 Set): {@code true} 반환 — 수집 억제 방향 오류 방지(REQ-007)
     *   <li>주말(토·일): {@code false}
     *   <li>NYSE 휴장일(observedHolidays 포함): {@code false}
     *   <li>평일 + 비휴장: {@code true}
     * </ul>
     *
     * @param date 판정할 날짜 (호출자 책임의 기준 시간대 — 해외는 ET 기준)
     * @return 개장일이면 {@code true}
     */
    @Override
    public boolean isOpenDay(LocalDate date) {
        Set<LocalDate> holidays = observedHolidaysRef.get();
        if (holidays.isEmpty()) {
            // fail-open: Set 초기화 미완료 → 수집 억제 금지 (REQ-007)
            return true;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(date);
    }

    /**
     * 현재 시각 기준 미국 시장이 열려 있는지 반환한다 (REQ-008).
     *
     * <p>판정: ET 평일 09:25(포함) ~ 16:05(미포함) AND 비NYSE 휴장.
     *
     * @return 미장 운영 중이면 {@code true}
     */
    @Override
    public boolean isMarketOpenNow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime nowEt = now.withZoneSameInstant(NEW_YORK);
        LocalDate today = nowEt.toLocalDate();
        // REQ-WM-030: 반일 조기폐장일은 13:00 ET 이후 폐장 — extraHolidays(전일 휴장)와 별개 경로, 기존 경로 불변
        boolean pastEarlyClose =
                earlyCloseDays.contains(today) && !nowEt.toLocalTime().isBefore(EARLY_CLOSE_TIME);
        return marketSchedule.isOverseasOpen(now) && isOpenDay(today) && !pastEarlyClose;
    }

    /**
     * NYSE 표준 10개 휴장일의 관측일(observed date)을 계산한다 (REQ-001~005).
     *
     * <p>패키지 가시성: 단위 테스트에서 연도별 검증용. 계산 자체는 {@link NyseHolidayAlgorithm}으로 추출됐다(♻️ 리팩터, 동작 무변경 —
     * SPEC-COLLECTOR-CALENDAR-001 TASK-004). 이 위임 메서드는 기존 단위 테스트가 참조하는 시그니처를 그대로 보존하기 위해 유지한다.
     *
     * @param year 계산 연도
     * @return 해당 연도의 NYSE 관측 휴장일 Set (주말 대체일 적용 완료)
     */
    Set<LocalDate> computeObservedHolidays(int year) {
        return NyseHolidayAlgorithm.computeObservedHolidays(year);
    }

    /**
     * US expected watermark(세션 마감이 지난 가장 최근 개장일)를 KST 자정 epoch 초로 계산한다(REQ-WM-006, stateless).
     *
     * <p>{@link #isOpenDay(LocalDate)}가 임의 과거 날짜를 결정론적으로 판정할 수 있어 캘린더 로드 상태와 무관하게 후방 탐색이 가능하다(§2 US
     * expected 후방 조회, `[EXISTING]`).
     */
    private double computeExpectedWatermarkEpoch() {
        LocalDate candidate = computeExpectedTradeDate();
        // 노출 값은 해당 거래일(candidate)의 KST 자정 epoch — US 세션 마감(ET) 기준 산출과 노출 인코딩을 분리한다(§11).
        return candidate == null ? Double.NaN : candidate.atStartOfDay(KST).toEpochSecond();
    }

    /**
     * US expected watermark의 기준 거래일을 계산한다(REQ-WM-006/010 — 커버리지 게이지의 분모 기준일로도 재사용).
     *
     * @return 세션 마감이 지난 가장 최근 개장일(ET 기준). holiday_count 미준비 상태면 {@code null}(REQ-WM-009 근사)
     */
    public LocalDate computeExpectedTradeDate() {
        if (observedHolidaysRef.get().size() < HOLIDAY_COUNT_READY_THRESHOLD) {
            // REQ-WM-009: holiday_count 미준비 → null 폴백(진성 absent는 registry field 없이 구현 불가 —
            // MarketSessionGate와 동일한 근거).
            return null;
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(NEW_YORK);
        LocalDate candidate =
                now.toLocalTime().isBefore(US_SESSION_CLOSE)
                        ? now.toLocalDate().minusDays(1)
                        : now.toLocalDate();
        for (int i = 0; i < MAX_LOOKBACK_DAYS && !isOpenDay(candidate); i++) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /**
     * US 일봉 전용 후퇴 기대 게이지 값을 KST 자정 epoch 초로 계산한다(SPEC-OBSV-WATERMARK-002 REQ-WM2-001, stateless).
     */
    private double computeExpectedWatermarkDailyEpoch() {
        LocalDate candidate = computePriorTradeDate();
        return candidate == null ? Double.NaN : candidate.atStartOfDay(KST).toEpochSecond();
    }

    /**
     * {@link #computeExpectedTradeDate()}(세션 마감이 지난 가장 최근 개장일)의 직전 개장일을
     * 계산한다(SPEC-OBSV-WATERMARK-002 §3.2, REQ-WM2-001/006). D+2 적재 지연을 반영해 US 일봉 전용 기대 게이지와 US 커버리지
     * 분모 기준일이 공유하는 단일 소스다(Decision 3). 달력일이 아닌 {@link #isOpenDay(LocalDate)} 개장일 캘린더로 후방
     * 탐색한다(REQ-WM2-006) — 기존 {@link #MAX_LOOKBACK_DAYS} 상한을 재사용한다.
     *
     * @return 직전 개장일. {@link #computeExpectedTradeDate()}가 {@code null}(holiday_count 미준비)이면 {@code
     *     null} 전파(REQ-WM2-002) @MX:NOTE 신규 메서드, 설계 Decision 3 구현체 — US 일봉 기대 게이지 및 커버리지 분모 단일 소스.
     */
    public LocalDate computePriorTradeDate() {
        LocalDate expected = computeExpectedTradeDate();
        if (expected == null) {
            return null;
        }
        LocalDate candidate = expected.minusDays(1);
        for (int i = 0; i < MAX_LOOKBACK_DAYS && !isOpenDay(candidate); i++) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /** gauge 조회 시 Micrometer가 호출하는 현재 장중 여부 계산. */
    private double computeMarketOpen() {
        return isMarketOpenNow() ? 1.0 : 0.0;
    }
}
