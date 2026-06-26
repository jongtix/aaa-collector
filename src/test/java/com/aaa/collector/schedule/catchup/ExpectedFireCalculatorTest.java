package com.aaa.collector.schedule.catchup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ExpectedFireCalculator — zone-aware 직전 발화 시각 계산 (SPEC-COLLECTOR-CATCHUP-001 T3)")
class ExpectedFireCalculatorTest {

    private ExpectedFireCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ExpectedFireCalculator();
    }

    @Nested
    @DisplayName("일간 cron — 평일 16:00 KST (domestic-daily-chain)")
    class DailyKstCron {

        private static final String CRON = "0 0 16 * * MON-FRI";
        private static final String ZONE = "Asia/Seoul";
        private static final ZoneId KST = ZoneId.of("Asia/Seoul");

        @Test
        @DisplayName("평일 18:00 KST에 조회하면 expectedLastFire = 당일 16:00 KST")
        void whenAfter16OnWeekday_thenSameDayFire() {
            // Arrange: 2026-06-22(월) 18:00:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 18, 0, 0, 0, KST).toInstant();

            // Act
            Optional<Instant> result = calculator.calculate(CRON, ZONE, now);

            // Assert
            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 22, 16, 0, 0, 0, KST).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }

        @Test
        @DisplayName("월요일 09:00 KST(16:00 이전)에 조회하면 expectedLastFire = 직전 금요일 16:00 KST")
        void whenBefore16OnMonday_thenPreviousFridayFire() {
            // Arrange: 2026-06-22(월) 09:00:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 9, 0, 0, 0, KST).toInstant();

            // Act
            Optional<Instant> result = calculator.calculate(CRON, ZONE, now);

            // Assert: 직전 금요일 2026-06-19 16:00 KST
            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 19, 16, 0, 0, 0, KST).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("주간 cron — 토요일 08:00 KST (domestic-financial-ratio)")
    class WeeklySaturdayCron {

        private static final String CRON = "0 0 8 * * SAT";
        private static final String ZONE = "Asia/Seoul";
        private static final ZoneId KST = ZoneId.of("Asia/Seoul");

        @Test
        @DisplayName("토요일 09:00 KST에 조회하면 expectedLastFire = 당일 토요일 08:00 KST")
        void whenAfter8OnSaturday_thenSameSaturdayFire() {
            // Arrange: 2026-06-20(토) 09:00:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 20, 9, 0, 0, 0, KST).toInstant();

            // Act
            Optional<Instant> result = calculator.calculate(CRON, ZONE, now);

            // Assert
            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 20, 8, 0, 0, 0, KST).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("주간 cron — 월요일 07:50 KST (domestic-etf-representative)")
    class WeeklyMondayCron {

        private static final String CRON = "0 50 7 * * MON";
        private static final String ZONE = "Asia/Seoul";
        private static final ZoneId KST = ZoneId.of("Asia/Seoul");

        @Test
        @DisplayName("월요일 09:00 KST에 조회하면 expectedLastFire = 당일 07:50 KST")
        void whenAfter750OnMonday_thenSameMondayFire() {
            // Arrange: 2026-06-22(월) 09:00:00 KST
            Instant now = ZonedDateTime.of(2026, 6, 22, 9, 0, 0, 0, KST).toInstant();

            // Act
            Optional<Instant> result = calculator.calculate(CRON, ZONE, now);

            // Assert
            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 22, 7, 50, 0, 0, KST).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("ET zone cron — 평일 10:00 ET (overseas-shortsale)")
    class DailyEtCron {

        private static final String CRON = "0 0 10 * * MON-FRI";
        private static final String ZONE = "America/New_York";
        private static final ZoneId ET = ZoneId.of("America/New_York");

        @Test
        @DisplayName("평일 12:00 ET에 조회하면 expectedLastFire = 당일 10:00 ET (Instant 비교)")
        void whenAfter10OnWeekday_thenSameDayFireInEt() {
            // Arrange: 2026-06-22(월) 12:00:00 ET
            Instant now = ZonedDateTime.of(2026, 6, 22, 12, 0, 0, 0, ET).toInstant();

            // Act
            Optional<Instant> result = calculator.calculate(CRON, ZONE, now);

            // Assert
            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 22, 10, 0, 0, 0, ET).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("에지 케이스")
    class EdgeCases {

        @Test
        @DisplayName("lookback 8일 이내에 발화 슬롯이 없으면 Optional.empty() 반환")
        void whenNoFireInLookback_thenEmptyOptional() {
            // "0 0 0 1 1 ?" 같은 상수는 Spring cron 6-필드 형식이 아니므로,
            // 매우 미래의 날짜에 발화하는 cron 대신 주어진 now보다 한참 먼 미래 now를 사용.
            // 2005-01-01 00:00 KST now → 2005 이전에는 매주 월~금 발화가 없는 경우 테스트하기 어려우므로
            // 대신 now = lookback 시작점 이전에는 슬롯이 없도록 now를 일요일 00:01로 설정하고
            // cron = 일요일 이외에만 발화하는 단기 cron이 아닌 방식으로 변경.
            // 실질적으로 8일 lookback 내에 항상 발화가 있는 cron에서 empty는 발생하지 않으므로,
            // 매우 긴 주기(예: 매월 31일)에서 now가 lookback 내 슬롯이 없을 때를 시뮬레이션.
            // Spring은 6-필드 cron을 사용하므로 "0 0 0 31 * ?" 은 매월 31일 자정만 발화.
            // 2026-06-15(월) 기준 lookback 8일(6/7~6/14)에 31일이 없으면 empty.
            String cron = "0 0 0 31 * ?";
            String zone = "Asia/Seoul";
            ZoneId kst = ZoneId.of("Asia/Seoul");
            // 2026-06-15(월) 12:00 KST: lookback 8일 내에 31일 없음 (6/7~6/14)
            Instant now = ZonedDateTime.of(2026, 6, 15, 12, 0, 0, 0, kst).toInstant();

            Optional<Instant> result = calculator.calculate(cron, zone, now);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("now가 정확히 발화 시각과 같으면 해당 시각이 반환된다 (경계 포함)")
        void whenNowIsExactlyFireTime_thenThatFireTimeReturned() {
            // Arrange: 2026-06-22(월) 16:00:00 KST — 정확히 발화 시각
            ZoneId kst = ZoneId.of("Asia/Seoul");
            Instant now = ZonedDateTime.of(2026, 6, 22, 16, 0, 0, 0, kst).toInstant();
            String cron = "0 0 16 * * MON-FRI";
            String zone = "Asia/Seoul";

            Optional<Instant> result = calculator.calculate(cron, zone, now);

            assertThat(result).isPresent();
            Instant expected = ZonedDateTime.of(2026, 6, 22, 16, 0, 0, 0, kst).toInstant();
            assertThat(result.get()).isEqualTo(expected);
        }

        @Test
        @DisplayName("결과는 항상 now 이전 또는 now와 같아야 한다")
        void resultIsAlwaysBeforeOrEqualToNow() {
            // 여러 cron 패턴에 대해 불변식 검증
            ZoneId kst = ZoneId.of("Asia/Seoul");
            Instant now = ZonedDateTime.of(2026, 6, 23, 10, 0, 0, 0, kst).toInstant();

            String[] crons = {
                "0 0 16 * * MON-FRI", "0 30 16 * * MON-FRI", "0 0 17 * * MON-FRI", "0 50 7 * * MON"
            };
            for (String cron : crons) {
                Optional<Instant> result = calculator.calculate(cron, "Asia/Seoul", now);
                result.ifPresent(
                        fire ->
                                assertThat(fire)
                                        .as("cron=%s 결과가 now보다 미래이면 안 됨", cron)
                                        .isBeforeOrEqualTo(now));
            }
        }

        @Test
        @DisplayName("LocalDateTime.now 시각과 1분 차이가 나도 올바른 발화를 반환한다")
        void oneMinuteBeforeFire_returnsCorrectPreviousFire() {
            // Arrange: 2026-06-22(월) 15:59 KST — 16:00 발화 직전
            ZoneId kst = ZoneId.of("Asia/Seoul");
            Instant now =
                    ZonedDateTime.of(2026, 6, 22, 15, 59, 0, 0, kst)
                            .toInstant()
                            .plusSeconds(59); // 15:59:59
            String cron = "0 0 16 * * MON-FRI";

            Optional<Instant> result = calculator.calculate(cron, "Asia/Seoul", now);

            // 당일 16:00 미경과 → 직전 금요일 16:00 KST
            assertThat(result).isPresent();
            LocalDateTime fireLocal = result.get().atZone(kst).toLocalDateTime();
            assertThat(fireLocal).isEqualTo(LocalDateTime.of(2026, 6, 19, 16, 0, 0));
        }
    }
}
