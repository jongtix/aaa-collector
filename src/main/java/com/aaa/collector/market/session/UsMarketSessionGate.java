package com.aaa.collector.market.session;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import com.aaa.collector.kis.websocket.KisMarketSchedule;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
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

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final KisMarketSchedule marketSchedule;
    private final Clock clock;

    /** 오버라이드 날짜 목록 — 불변 복사본으로 보관 (EI_EXPOSE_REP2 방지). */
    private final List<LocalDate> extraHolidays;

    /**
     * 알고리즘 계산 + 오버라이드를 합산한 불변 NYSE 휴장일 Set.
     *
     * <p>초기값 {@code Set.of()}(빈 Set)은 fail-open 신호(REQ-007). {@link #init()} 완료 후 20+ 날짜를 포함한다.
     * {@link AtomicReference}로 단일 쓰기(init) → 다중 읽기(Gauge 스크랩·isOpenDay) happens-before 보장.
     */
    private final AtomicReference<Set<LocalDate>> observedHolidaysRef =
            new AtomicReference<>(Set.of());

    /**
     * 생성자. 두 게이지를 registry에 등록한다 — API 호출 없음(REQ-010).
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
        this.marketSchedule = marketSchedule;
        this.clock = clock;
        // 불변 복사본으로 보관 — EI_EXPOSE_REP2 방지 (SpotBugs)
        this.extraHolidays = List.copyOf(properties.getExtraHolidays());

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
        log.info(
                "[us-market-gate] NYSE 휴장일 Set 초기화 완료 — count={}, year={}/{}",
                observedHolidaysRef.get().size(),
                year,
                year + 1);
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
        if (!marketSchedule.isOverseasOpen(now)) {
            return false;
        }
        LocalDate today = now.withZoneSameInstant(NEW_YORK).toLocalDate();
        return isOpenDay(today);
    }

    /**
     * NYSE 표준 10개 휴장일의 관측일(observed date)을 계산한다 (REQ-001~005).
     *
     * <p>패키지 가시성: 단위 테스트에서 연도별 검증용.
     *
     * @param year 계산 연도
     * @return 해당 연도의 NYSE 관측 휴장일 Set (주말 대체일 적용 완료)
     */
    Set<LocalDate> computeObservedHolidays(int year) {
        Set<LocalDate> holidays = new HashSet<>();

        // 1. New Year's Day — Jan 1 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JANUARY, 1)));

        // 2. MLK Day — 1월 3번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3));

        // 3. Presidents' Day — 2월 3번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));

        // 4. Good Friday — 부활절 2일 전 (Meeus/Jones/Butcher, REQ-001/003)
        holidays.add(easterSunday(year).minusDays(2));

        // 5. Memorial Day — 5월 마지막 월요일 (REQ-001)
        holidays.add(lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY));

        // 6. Juneteenth — Jun 19 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JUNE, 19)));

        // 7. Independence Day — Jul 4 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.JULY, 4)));

        // 8. Labor Day — 9월 첫 번째 월요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));

        // 9. Thanksgiving — 11월 4번째 목요일 (REQ-001)
        holidays.add(nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));

        // 10. Christmas Day — Dec 25 (REQ-001)
        holidays.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));

        return holidays;
    }

    /** gauge 조회 시 Micrometer가 호출하는 현재 장중 여부 계산. */
    private double computeMarketOpen() {
        return isMarketOpenNow() ? 1.0 : 0.0;
    }

    /**
     * 토요일 → 직전 금요일, 일요일 → 다음 월요일, 평일 → 그대로 (REQ-004/005).
     *
     * @param date 원래 휴장일
     * @return 관측(observed) 휴장일
     */
    private static LocalDate observed(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1); // Friday
            case SUNDAY -> date.plusDays(1); // Monday
            default -> date;
        };
    }

    /**
     * 지정 연월의 n번째 특정 요일을 반환한다.
     *
     * @param year 연도
     * @param month 월
     * @param dow 요일
     * @param n 몇 번째 (1-based)
     * @return n번째 해당 요일 날짜
     */
    private static LocalDate nthWeekdayOfMonth(int year, Month month, DayOfWeek dow, int n) {
        LocalDate first = LocalDate.of(year, month, 1);
        int daysUntilDow = (dow.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        return first.plusDays(daysUntilDow + (n - 1) * 7);
    }

    /**
     * 지정 연월의 마지막 특정 요일을 반환한다.
     *
     * @param year 연도
     * @param month 월
     * @param dow 요일
     * @return 해당 월의 마지막 해당 요일 날짜
     */
    private static LocalDate lastWeekdayOfMonth(int year, Month month, DayOfWeek dow) {
        LocalDate last = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        int daysBack = (last.getDayOfWeek().getValue() - dow.getValue() + 7) % 7;
        return last.minusDays(daysBack);
    }

    /**
     * Meeus/Jones/Butcher 알고리즘으로 부활절 일요일을 계산한다 (REQ-003).
     *
     * @param year 연도
     * @return 해당 연도의 부활절 일요일
     */
    private static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
