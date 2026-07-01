package com.aaa.collector.stock.shortsale.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.gate.UsMarketOpenGate;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleOverseasScheduler")
class ShortSaleOverseasSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private ShortSaleOverseasDailyCollectionService dailyService;
    @Mock private ShortSaleOverseasInterestCollectionService interestService;
    @Mock private UsMarketOpenGate usMarketOpenGate;

    @Nested
    @DisplayName("cron 발화 트리거")
    class Trigger {

        @Test
        @DisplayName("Daily는 전영업일, Interest는 발화일로 각 1회 순차 호출한다 (REQ-SSO-010/-014, 이슈 #59)")
        void callsDailyThenInterestOnce() {
            // Arrange: 2026-01-06(화) 10:00 ET 발화 — 전영업일은 2026-01-05(월)
            Clock clock = Clock.fixed(Instant.parse("2026-01-06T15:00:00Z"), ET);
            when(usMarketOpenGate.isOpenDay(LocalDate.of(2026, 1, 5))).thenReturn(true);
            ShortSaleOverseasScheduler scheduler =
                    new ShortSaleOverseasScheduler(
                            dailyService, interestService, usMarketOpenGate, clock);

            // Act
            scheduler.collect();

            // Assert: Daily(전영업일) 먼저, Interest(발화일) 다음 — 각 1회
            InOrder order = inOrder(dailyService, interestService);
            order.verify(dailyService).collectDaily(LocalDate.of(2026, 1, 5));
            order.verify(interestService).collectShortInterest(LocalDate.of(2026, 1, 6));
            verifyNoMoreInteractions(dailyService, interestService);
        }

        @Test
        @DisplayName("월요일 발화 시 주말을 건너뛰어 금요일을 Daily 기준일로 사용한다 (이슈 #59)")
        void skipsWeekendForDailyTradeDate() {
            // Arrange: 2026-01-05(월) 10:00 ET 발화 — 토(01-03)/일(01-04) 휴장, 전영업일은 금(01-02)
            Clock clock = Clock.fixed(Instant.parse("2026-01-05T15:00:00Z"), ET);
            when(usMarketOpenGate.isOpenDay(LocalDate.of(2026, 1, 4))).thenReturn(false);
            when(usMarketOpenGate.isOpenDay(LocalDate.of(2026, 1, 3))).thenReturn(false);
            when(usMarketOpenGate.isOpenDay(LocalDate.of(2026, 1, 2))).thenReturn(true);
            ShortSaleOverseasScheduler scheduler =
                    new ShortSaleOverseasScheduler(
                            dailyService, interestService, usMarketOpenGate, clock);

            // Act
            scheduler.collect();

            // Assert
            verify(dailyService).collectDaily(LocalDate.of(2026, 1, 2));
        }
    }

    @Nested
    @DisplayName("ET 거래일 산정 (KST 비교 금지)")
    class TradeDate {

        @Test
        @DisplayName("ET와 KST 날짜가 갈리는 instant에서 ET 거래일 기준으로 산정한다")
        void usesEtNotKst() {
            // Arrange: 2026-01-06 23:30 ET == 2026-01-07 13:30 KST (KST로 산정하면 날짜가 하루 뒤집힘)
            Instant instant = Instant.parse("2026-01-07T04:30:00Z");
            assertThat(LocalDate.ofInstant(instant, ET)).isEqualTo(LocalDate.of(2026, 1, 6));
            assertThat(LocalDate.ofInstant(instant, KST)).isEqualTo(LocalDate.of(2026, 1, 7));

            Clock clock = Clock.fixed(instant, ET);
            when(usMarketOpenGate.isOpenDay(LocalDate.of(2026, 1, 5))).thenReturn(true);
            ShortSaleOverseasScheduler scheduler =
                    new ShortSaleOverseasScheduler(
                            dailyService, interestService, usMarketOpenGate, clock);

            // Act
            scheduler.collect();

            // Assert: ET 거래일(01-06) 기준 — Interest는 발화일(01-06), Daily는 전영업일(01-05).
            // KST(01-07)로 새면 둘 다 RED
            verify(dailyService).collectDaily(LocalDate.of(2026, 1, 5));
            verify(interestService).collectShortInterest(LocalDate.of(2026, 1, 6));
        }
    }

    @Nested
    @DisplayName("예외 흡수 (REQ-SSO-033)")
    class ExceptionAbsorption {

        @Test
        @DisplayName("Daily 수집 예외가 던져져도 흡수해 스레드가 종료되지 않는다")
        void absorbsCollectionException() {
            // Arrange
            Clock clock = Clock.fixed(Instant.parse("2026-01-06T15:00:00Z"), ET);
            lenient().when(usMarketOpenGate.isOpenDay(any())).thenReturn(true);
            ShortSaleOverseasScheduler scheduler =
                    new ShortSaleOverseasScheduler(
                            dailyService, interestService, usMarketOpenGate, clock);
            doThrow(new RuntimeException("FINRA 장애")).when(dailyService).collectDaily(any());

            // Act & Assert: 예외가 전파되지 않음(흡수)
            assertThatCode(scheduler::collect).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("스케줄 방식 (REQ-SSO-033)")
    class ScheduleAnnotation {

        @Test
        @DisplayName("@Scheduled cron만 사용하고 fixedDelay/fixedRate를 쓰지 않는다")
        void usesCronOnly() throws NoSuchMethodException {
            Method collect = ShortSaleOverseasScheduler.class.getMethod("collect");
            Scheduled scheduled = collect.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 10 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
            // fixedDelay/fixedRate 미사용(미설정 시 -1)
            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
        }
    }
}
