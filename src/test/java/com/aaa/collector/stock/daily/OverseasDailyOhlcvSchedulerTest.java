package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasDailyOhlcvScheduler 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-OHLCV-001)")
class OverseasDailyOhlcvSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private OverseasDailyOhlcvCollectionService collectionService;
    @Mock private DailyCompletePublisher publisher;

    /** 기본 스케줄러 — 프로덕션과 동일한 실시간 Clock.system(KST)로 생성. */
    private OverseasDailyOhlcvScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler =
                new OverseasDailyOhlcvScheduler(collectionService, publisher, Clock.system(KST));
    }

    @Nested
    @DisplayName("cron 어노테이션 검증 (AC-SCHED-1, REQ-OVOH-020/-032)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 30 16 * * MON-FRI', zone='America/New_York' (ET 마감+30분)")
        void collectDaily_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = OverseasDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 30 16 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 (REQ-OVOH-020)")
        void collectDaily_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = OverseasDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectDaily — 수집 흐름")
    class CollectDailyFlow {

        @Test
        @DisplayName("진입점 발화 — collect 정확히 1회 호출 (AC-SCHED-3, REQ-OVOH-010)")
        void collectDaily_callsCollectExactlyOnce() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            // Act
            scheduler.collectDaily();

            // Assert
            verify(collectionService, times(1)).collect(any(LocalDate.class));
        }

        @Test
        @DisplayName("정상 흐름 — collect 후 overseas 발행 (AC-EVT-1)")
        void collectDaily_normalFlow_publishesOverseas() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            // Act
            scheduler.collectDaily();

            // Assert
            verify(publisher).publish(result, "overseas");
        }

        @Test
        @DisplayName("부분 성공도 발행 (AC-EVT-2, REQ-OVOH-040)")
        void collectDaily_partialSuccess_stillPublishes() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 3, 2);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            // Act
            scheduler.collectDaily();

            // Assert
            verify(publisher).publish(eq(result), eq("overseas"));
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 미종료 (AC-SCHED-2, REQ-OVOH-041)")
        void collectDaily_exceptionInCollect_absorbed() {
            // Arrange
            Mockito.when(collectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("수집 예외"));

            // Act & Assert
            assertThatCode(scheduler::collectDaily).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("발행 실패 — 흡수, 스케줄러 미종료 (AC-EVT-3, REQ-OVOH-014)")
        void collectDaily_publishFailure_absorbed() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);
            doThrow(new RuntimeException("발행 실패"))
                    .when(publisher)
                    .publish(any(CollectionResult.class), eq("overseas"));

            // Act & Assert
            assertThatCode(scheduler::collectDaily).doesNotThrowAnyException();
        }
    }

    /**
     * AC-VAL-3: ET zone 회귀 가드.
     *
     * <p>cron 16:30 ET 발화 시각은 서버 KST 익일 새벽이다 — 예를 들어 2026-06-18 16:30 ET = 2026-06-19 05:30 KST.
     * 스케줄러가 {@code LocalDate.now()} / {@code LocalDate.now(KST)}를 사용하면 KST 기준 익일(2026-06-19)이 반환되고,
     * ET 기준 당일(2026-06-18) 행 가드가 무력화되어 미마감 스냅샷이 적재된다(REQ-OVOH-015).
     *
     * <p>이 테스트는 cron 발화 시각과 동일한 Instant({@code 2026-06-18 16:30 ET = 2026-06-19 05:30 KST})를
     * Clock.fixed로 고정하고, {@code collect(today)}에 전달된 {@code today}가 ET 날짜(2026-06-18)임을 검증한다. 스케줄러
     * 내부에서 {@code LocalDate.now()} 또는 {@code LocalDate.now(KST)}로 변경하면 이 테스트가 RED가 된다.
     */
    @Nested
    @DisplayName("ET zone 회귀 가드 (AC-VAL-3, REQ-OVOH-015)")
    class EtZoneRegressionGuard {

        @Test
        @DisplayName("cron 16:30 ET 발화 Instant — collect에 전달된 today가 ET 날짜(전일 KST 아님)")
        void collectDaily_usesEtZone_notKstOrSystem() {
            // Arrange
            // 2026-06-18 16:30:00 America/New_York = 2026-06-19 05:30:00 Asia/Seoul
            // ET 날짜: 2026-06-18  /  KST 날짜: 2026-06-19 ← 이 둘이 달라야 회귀가 검출된다
            Instant firingInstant = ZonedDateTime.of(2026, 6, 18, 16, 30, 0, 0, ET).toInstant();
            LocalDate expectedEtDate = LocalDate.of(2026, 6, 18);
            LocalDate kstDate = LocalDate.ofInstant(firingInstant, KST);
            // 전제: ET 날짜와 KST 날짜가 실제로 다른지 확인 (테스트 자체의 전제 검증)
            assertThat(kstDate).isNotEqualTo(expectedEtDate);

            Clock fixedClock = Clock.fixed(firingInstant, ET);
            OverseasDailyOhlcvScheduler schedulerUnderTest =
                    new OverseasDailyOhlcvScheduler(collectionService, publisher, fixedClock);

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            CollectionResult result = new CollectionResult(1, 1, 0);
            Mockito.when(collectionService.collect(dateCaptor.capture())).thenReturn(result);

            // Act
            schedulerUnderTest.collectDaily();

            // Assert — collect에 전달된 날짜는 ET 날짜(2026-06-18)이어야 한다. KST 날짜(2026-06-19)면 회귀.
            assertThat(dateCaptor.getValue()).isEqualTo(expectedEtDate);
        }
    }
}
