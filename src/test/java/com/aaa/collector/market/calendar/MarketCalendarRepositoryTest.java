package com.aaa.collector.market.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link MarketCalendar} 엔티티·리포지토리 통합 테스트 (SPEC-COLLECTOR-CALENDAR-001 TASK-002, AC-1).
 *
 * <p>V39 마이그레이션의 {@code ddl-auto=validate} 통과 여부도 이 테스트가 컨텍스트 로드 시점에 함께 검증한다(AC-1). 전용
 * Testcontainers 컨테이너 — 아직 Tier-2 grant 대상이 아니므로(TASK-010에서 등재) 기본(root) 데이터소스로 자유롭게 INSERT/UPDATE
 * 가능하다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("MarketCalendarRepository 통합 테스트 (V39 market_calendar DDL 매핑)")
@Tag("integration")
class MarketCalendarRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;

    @Autowired private MarketCalendarRepository marketCalendarRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM market_calendar");
    }

    private static MarketCalendar row(
            CalendarCode code, LocalDate date, boolean open, CalendarSource source) {
        return MarketCalendar.builder()
                .calendarCode(code)
                .calDate(date)
                .open(open)
                .source(source)
                .build();
    }

    @Nested
    @DisplayName("전체 일자 스키마 — 휴장일만 저장되지 않는다 (REQ-CAL-001, AC-1)")
    class FullDateSchema {

        @Test
        @DisplayName("2026년 1월(31일) 전체를 시딩하면 정확히 31행이 저장된다")
        void seedingWholeMonth_storesRowPerDay() {
            for (int day = 1; day <= 31; day++) {
                LocalDate date = LocalDate.of(2026, Month.JANUARY, day);
                boolean open = date.getDayOfWeek().getValue() < 6; // 주말만 휴장으로 단순화(테스트 전용)
                marketCalendarRepository.saveAndFlush(
                        row(CalendarCode.KRX, date, open, CalendarSource.KIS_API));
            }

            List<MarketCalendar> found =
                    marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

            assertThat(found).hasSize(31);
            assertThat(found).allSatisfy(m -> assertThat(m.getSource()).isNotNull());
        }

        @Test
        @DisplayName("PK (calendar_code, cal_date) 중복 INSERT 시 제약 위반 — DB 레벨 유일성 (AC-1)")
        void duplicateKey_violatesPrimaryKeyConstraint() {
            // Note: 리포지토리 save()는 수동 할당 복합 키라 JPA merge 경로를 타 upsert처럼 동작한다(정상 JPA 시맨틱스,
            // MarketCalendarService의 조회-후-분기 upsert가 이를 전제로 설계됐다) — 그래서 PK 유일성 자체는 DB 레벨
            // 원시 INSERT로 직접 검증한다.
            LocalDate date = LocalDate.of(2026, 3, 2);
            String insertSql =
                    "INSERT INTO market_calendar (calendar_code, cal_date, is_open, source, created_at,"
                            + " updated_at) VALUES ('KRX', ?, true, 'KIS_API', NOW(), NOW())";
            jdbcTemplate.update(insertSql, date);

            assertThatThrownBy(() -> jdbcTemplate.update(insertSql, date))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("동일 날짜라도 calendar_code가 다르면 독립 행으로 저장된다 (EC-3)")
        void sameDateDifferentCalendarCode_storedIndependently() {
            LocalDate date = LocalDate.of(2026, 6, 19); // Juneteenth — 미국 휴장, 한국은 평시
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, date, true, CalendarSource.KIS_API));
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.NYSE, date, false, CalendarSource.ALGORITHM));

            assertThat(
                            marketCalendarRepository.findByCalendarCodeAndCalDate(
                                    CalendarCode.KRX, date))
                    .isPresent()
                    .get()
                    .satisfies(m -> assertThat(m.isOpen()).isTrue());
            assertThat(
                            marketCalendarRepository.findByCalendarCodeAndCalDate(
                                    CalendarCode.NYSE, date))
                    .isPresent()
                    .get()
                    .satisfies(m -> assertThat(m.isOpen()).isFalse());
        }
    }

    @Nested
    @DisplayName("enum 컨버터 — DB 저장값이 enum.name() 문자열이다 (ADR-010)")
    class EnumConverterStorage {

        @Test
        @DisplayName("calendar_code/source 컬럼 raw 값이 정확히 enum 상수명이다")
        void rawColumnValues_matchEnumName() {
            LocalDate date = LocalDate.of(2026, 4, 10);
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.NYSE, date, false, CalendarSource.MANUAL));

            String rawCalendarCode =
                    jdbcTemplate.queryForObject(
                            "SELECT calendar_code FROM market_calendar WHERE cal_date = ?",
                            String.class,
                            date);
            String rawSource =
                    jdbcTemplate.queryForObject(
                            "SELECT source FROM market_calendar WHERE cal_date = ?",
                            String.class,
                            date);

            assertThat(rawCalendarCode).isEqualTo("NYSE");
            assertThat(rawSource).isEqualTo("MANUAL");
        }
    }

    @Nested
    @DisplayName("범위 조회 — findByCalendarCodeAndCalDateBetween")
    class RangeQuery {

        @Test
        @DisplayName("경계값(시작·끝 포함) 범위 내 행만 반환한다")
        void betweenQuery_isInclusiveOnBothBounds() {
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, LocalDate.of(2026, 5, 9), true, CalendarSource.KIS_API));
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, LocalDate.of(2026, 5, 10), true, CalendarSource.KIS_API));
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, LocalDate.of(2026, 5, 11), true, CalendarSource.KIS_API));
            // 범위 밖 — 결과에 나타나지 않아야 한다
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, LocalDate.of(2026, 5, 8), true, CalendarSource.KIS_API));
            marketCalendarRepository.saveAndFlush(
                    row(CalendarCode.KRX, LocalDate.of(2026, 5, 12), true, CalendarSource.KIS_API));

            List<MarketCalendar> found =
                    marketCalendarRepository.findByCalendarCodeAndCalDateBetween(
                            CalendarCode.KRX, LocalDate.of(2026, 5, 9), LocalDate.of(2026, 5, 11));

            assertThat(found)
                    .extracting(MarketCalendar::getCalDate)
                    .containsExactlyInAnyOrder(
                            LocalDate.of(2026, 5, 9),
                            LocalDate.of(2026, 5, 10),
                            LocalDate.of(2026, 5, 11));
        }

        @Test
        @DisplayName("존재하지 않는 조합 조회 시 empty")
        void missingRow_returnsEmpty() {
            Optional<MarketCalendar> found =
                    marketCalendarRepository.findByCalendarCodeAndCalDate(
                            CalendarCode.NYSE, LocalDate.of(2099, 1, 1));

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("BaseEntity 감사 필드 + round-trip 매핑")
    class EntityMapping {

        @Test
        @DisplayName("모든 필드 round-trip 저장·조회 일치 + 감사 필드 채워짐")
        void allFields_roundTrip() {
            MarketCalendar saved =
                    marketCalendarRepository.saveAndFlush(
                            row(
                                    CalendarCode.KRX,
                                    LocalDate.of(2026, 2, 16),
                                    false,
                                    CalendarSource.DERIVED));

            MarketCalendar found =
                    marketCalendarRepository
                            .findByCalendarCodeAndCalDate(
                                    CalendarCode.KRX, LocalDate.of(2026, 2, 16))
                            .orElseThrow();

            assertThat(found.getCalendarCode()).isEqualTo(saved.getCalendarCode());
            assertThat(found.getCalDate()).isEqualTo(saved.getCalDate());
            assertThat(found.isOpen()).isFalse();
            assertThat(found.getSource()).isEqualTo(CalendarSource.DERIVED);
            assertThat(found.getCreatedAt()).isNotNull();
            assertThat(found.getUpdatedAt()).isNotNull();
        }
    }
}
