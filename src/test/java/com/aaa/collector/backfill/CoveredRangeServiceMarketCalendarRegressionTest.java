package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.common.gate.MarketOpenGate;
import com.aaa.collector.market.calendar.CalendarCode;
import com.aaa.collector.market.calendar.CalendarSource;
import com.aaa.collector.market.calendar.MarketCalendar;
import com.aaa.collector.market.calendar.MarketCalendarRepository;
import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.session.MarketSessionGateRefresher;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code market_calendar} 백엔드 전환 비회귀 검증 — {@code CoveredRangeService}가 실제로 호출하는 {@link
 * MarketOpenGate}(실 구현 {@code MarketSessionGate})의 판정 결과가 배포 전후 동일함을 실 시딩 데이터로 증명한다
 * (SPEC-COLLECTOR-CALENDAR-001 TASK-011, AC-16/AC-17).
 *
 * <p><b>중요</b>: {@link CoveredRangeServiceGapWalkTest}·{@link CoveredRangeServiceTest}와 달리 이 테스트는
 * {@code MarketSessionGate}/{@code UsMarketSessionGate}를 {@code @MockitoBean}으로 대체하지 <b>않는다</b> —
 * TASK-007이 교체한 실제 판정 백엔드({@code market_calendar} 테이블 조회)를 Testcontainers로 재현해야 하기 때문이다.
 *
 * <p>검증 구간 2개(D10 핵심):
 *
 * <ul>
 *   <li><b>AC-16 — 수년 전 커서</b>: 게이트 범위({@code [오늘−14, 오늘+20]}, REQ-CAL-036) 밖. {@code
 *       market_calendar}에 {@code is_open=false}로 시딩해도 게이트 캐시에 반영되지 않아 {@code isOpenDay}가
 *       fail-open({@code true})을 유지한다 — 시딩 전과 동일.
 *   <li><b>AC-17 — 4~14일 전 커서(D10 신규)</b>: 게이트 범위 <b>안</b>. {@code is_open=false} 시딩이 게이트 캐시에 정확히
 *       반영되어 {@code isOpenDay}가 실제 값({@code false})을 반환한다 — fail-open으로 퇴화하지 않는다.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName(
        "CoveredRangeService·MarketSessionGate — market_calendar 백엔드 전환 비회귀"
                + " (SPEC-COLLECTOR-CALENDAR-001 TASK-011)")
@Tag("integration")
class CoveredRangeServiceMarketCalendarRegressionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private BackfillMetrics backfillMetrics;

    /** 실 구현 — 이 테스트의 핵심: 절대 {@code @MockitoBean}으로 대체하지 않는다. */
    @Autowired private MarketOpenGate marketOpenGate;

    @Autowired private MarketSessionGateRefresher marketSessionGateRefresher;
    @Autowired private MarketCalendarRepository marketCalendarRepository;
    @Autowired private CoveredRangeService coveredRangeService;
    @Autowired private BackfillStatusRepository backfillStatusRepository;

    @BeforeEach
    void cleanUp() throws SQLException {
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
        JdbcTemplate root = RootFixtureCleaner.rootJdbcTemplate(MYSQL.getJdbcUrl());
        root.update("DELETE FROM market_calendar");
    }

    private LocalDate today() {
        return LocalDate.now(KST);
    }

    private void seed(LocalDate date, boolean isOpen) {
        marketCalendarRepository.save(
                MarketCalendar.builder()
                        .calendarCode(CalendarCode.KRX)
                        .calDate(date)
                        .open(isOpen)
                        .source(CalendarSource.MANUAL)
                        .build());
    }

    @Nested
    @DisplayName("AC-16 — 수년 전 커서: 게이트 범위 밖, fail-open 유지(배포 전후 동일)")
    class YearsAgoCursor {

        @Test
        @DisplayName("수년 전 날짜를 is_open=false로 시딩해도 isOpenDay는 여전히 true(fail-open)를 반환한다")
        void yearsAgoDate_seededClosed_stillFailOpenTrue() {
            // Arrange
            LocalDate yearsAgo = today().minusYears(3);
            seed(yearsAgo, false);

            // Act — 게이트 캐시 갱신(REQ-CAL-036 범위: 오늘−14~오늘+20만 재조회)
            marketSessionGateRefresher.refresh();

            // Assert — 게이트 캐시 범위 밖이라 시딩값이 반영되지 않고 fail-open 유지
            assertThat(marketOpenGate.isOpenDay(yearsAgo)).isTrue();
            // isOpenDayStrict는 리포지토리를 직접 조회하므로 실제 값을 정확히 반환한다(대조)
            assertThat(marketOpenGate.isOpenDayStrict(yearsAgo)).contains(false);
        }

        @Test
        @DisplayName(
                "CoveredRangeService.walkGapForward의 SINGLE_DATE 분기가 수년 전 커서를 skip하지 않는다(fail-open)")
        void walkGapForward_singleDateMode_doesNotSkipYearsAgoCursor() {
            // Arrange — covered_until_date를 수년 전 날짜 직전으로 설정, today 파라미터도 그 날짜로 고정해 커서가
            // 정확히 수년 전 날짜에 머무르게 한다
            LocalDate yearsAgo = today().minusYears(3);
            seed(yearsAgo, false);
            marketSessionGateRefresher.refresh();

            BackfillStatus status =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("MARKET_INDICATOR")
                                    .targetCode("USDKRW")
                                    .dataTable("market_indicators")
                                    .status(BackfillStatusType.IN_PROGRESS)
                                    .lastCollectedDate(yearsAgo.minusDays(30))
                                    .build());
            status.advanceCoveredUntil(yearsAgo.minusDays(1));
            status = backfillStatusRepository.saveAndFlush(status);

            RecordingFiller filler = new RecordingFiller();

            // Act
            coveredRangeService.walkGapForward(status, filler, yearsAgo);

            // Assert — fail-open(true)이므로 "비거래일 skip" 조건(!isOpenDay)이 성립하지 않아 필러가 호출된다
            assertThat(filler.cursorsCalled).containsExactly(yearsAgo);
        }
    }

    @Nested
    @DisplayName("AC-17(D10 신규) — 4~14일 전 커서: 게이트 범위 안, 정확한 값 반환(fail-open으로 퇴화하지 않음)")
    class RecentPastCursor {

        @Test
        @DisplayName("4~14일 전 날짜를 is_open=false로 시딩하면 isOpenDay가 정확히 false를 반환한다")
        void withinGateRange_seededClosed_returnsAccurateFalse() {
            // Arrange — 게이트 범위(오늘−14~오늘+20) 안쪽, D10이 특별히 다루는 구간
            LocalDate withinRange = today().minusDays(10);
            seed(withinRange, false);

            // Act
            marketSessionGateRefresher.refresh();

            // Assert — 게이트 캐시 범위 안이므로 시딩값이 정확히 반영된다(fail-open 아님)
            assertThat(marketOpenGate.isOpenDay(withinRange)).isFalse();
            assertThat(marketOpenGate.isOpenDayStrict(withinRange)).contains(false);
        }

        @Test
        @DisplayName("게이트 범위 하한 경계(오늘−14일)도 정확한 값을 반환한다 — 하한을 좁히면 이 케이스부터 회귀한다")
        void gateLowerBoundary_returnsAccurateValue() {
            LocalDate lowerBoundary = today().minusDays(14);
            seed(lowerBoundary, false);

            marketSessionGateRefresher.refresh();

            assertThat(marketOpenGate.isOpenDay(lowerBoundary)).isFalse();
        }

        @Test
        @DisplayName(
                "CoveredRangeService.walkGapForward의 SINGLE_DATE 분기가 4~14일 전 휴장일 커서를 정확히 skip한다")
        void walkGapForward_singleDateMode_skipsAccurateHolidayCursor() {
            // Arrange
            LocalDate holiday = today().minusDays(10);
            LocalDate dayAfterHoliday = holiday.plusDays(1);
            seed(holiday, false);
            marketSessionGateRefresher.refresh();

            BackfillStatus status =
                    backfillStatusRepository.saveAndFlush(
                            BackfillStatus.builder()
                                    .targetType("MARKET_INDICATOR")
                                    .targetCode("USDKRW")
                                    .dataTable("market_indicators")
                                    .status(BackfillStatusType.IN_PROGRESS)
                                    .lastCollectedDate(holiday.minusDays(30))
                                    .build());
            status.advanceCoveredUntil(holiday.minusDays(1));
            status = backfillStatusRepository.saveAndFlush(status);

            RecordingFiller filler = new RecordingFiller();

            // Act
            coveredRangeService.walkGapForward(status, filler, dayAfterHoliday);

            // Assert — holiday(휴장)는 skip되고 dayAfterHoliday만 필러 호출
            assertThat(filler.cursorsCalled).containsExactly(dayAfterHoliday);
        }
    }

    /** 호출된 커서를 기록하는 단일 날짜형 필러 — 항상 1일치 성공을 반환한다. */
    private static final class RecordingFiller implements CoveredGapFiller {
        private final List<LocalDate> cursorsCalled = new java.util.ArrayList<>();

        @Override
        public CoveredFillResult persistStep(LocalDate cursor) {
            cursorsCalled.add(cursor);
            return new CoveredFillResult(1, 1, cursor);
        }
    }
}
