package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link ShortSaleDomesticT0RevisionScheduler} 단위 테스트
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-011, -030, plan.md §M7).
 *
 * <p>{@code FinraCdnShortSaleBackfillSchedulerTest} 구조를 답습하고, 이 스케줄러 고유의 {@code
 * closing_window_end_date} 매 실행 재조회(캐싱 없음)를 추가로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleDomesticT0RevisionScheduler")
class ShortSaleDomesticT0RevisionSchedulerTest {

    @Mock private ShortSaleDomesticT0RevisionCorrectionService t0RevisionCorrectionService;
    @Mock private T0rCorrectionStatusRepository t0rCorrectionStatusRepository;

    private ShortSaleDomesticT0RevisionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler =
                new ShortSaleDomesticT0RevisionScheduler(
                        t0RevisionCorrectionService, t0rCorrectionStatusRepository);
    }

    private T0rCorrectionStatus markerRow(LocalDate closingWindowEndDate) {
        return new T0rCorrectionStatus(
                T0rCorrectionStatus.SINGLETON_ID, closingWindowEndDate, null);
    }

    @Nested
    @DisplayName("@Scheduled cron 전용")
    class ScheduledAnnotation {

        @Test
        @DisplayName("run 메서드에 전용 cron 프로퍼티(기본 0 30 20 * * *, Asia/Seoul) 존재")
        void run_hasCronAnnotationWithDefaults() throws NoSuchMethodException {
            Method method = ShortSaleDomesticT0RevisionScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron())
                    .isEqualTo("${aaa.shortsale-domestic.t0r-revision.cron:0 30 20 * * *}");
            assertThat(scheduled.zone())
                    .isEqualTo("${aaa.shortsale-domestic.t0r-revision.zone:Asia/Seoul}");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate는 사용하지 않는다(cron 전용 정적 확인)")
        void run_neverUsesFixedDelayOrFixedRate() throws NoSuchMethodException {
            Method method = ShortSaleDomesticT0RevisionScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRate()).isEqualTo(-1);
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("closing_window_end_date 매 실행 재조회 (REQ-T0R-011, plan.md §M5 대상 재확인 절차)")
    class ClosingWindowEndDateResolution {

        @Test
        @DisplayName("마커 행 존재 — 조회된 closing_window_end_date를 그대로 서비스에 전달(캐싱 없음)")
        void markerPresent_passesDateVerbatimEachRun() {
            LocalDate day1 = LocalDate.of(2026, 8, 6);
            LocalDate day2 = LocalDate.of(2026, 8, 7);
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.of(markerRow(day1)))
                    .thenReturn(Optional.of(markerRow(day2)));

            scheduler.run();
            scheduler.run();

            verify(t0RevisionCorrectionService).correctT0Revisions(day1);
            verify(t0RevisionCorrectionService).correctT0Revisions(day2);
        }

        @Test
        @DisplayName("t0r_correction_status 행 없음 — 서비스를 호출하지 않고 skip")
        void markerRowMissing_skipsWithoutCallingService() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.empty());

            assertThatCode(scheduler::run).doesNotThrowAnyException();

            verifyNoInteractions(t0RevisionCorrectionService);
        }
    }

    @Nested
    @DisplayName("예외 흡수")
    class ExceptionAbsorption {

        @Test
        @DisplayName("서비스 예외 — 스케줄러 스레드로 전파되지 않는다")
        void serviceException_doesNotPropagate() {
            LocalDate day = LocalDate.of(2026, 8, 6);
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.of(markerRow(day)));
            doThrow(new RuntimeException("kis error"))
                    .when(t0RevisionCorrectionService)
                    .correctT0Revisions(day);

            assertThatCode(scheduler::run).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AtomicBoolean single-flight 가드 (독립성)")
    class SingleFlightGuard {

        @Test
        @DisplayName("실행 중(running=true) 재진입 시 서비스를 다시 호출하지 않는다")
        void reentrantCallWhileRunning_skipsServiceInvocation() {
            LocalDate day = LocalDate.of(2026, 8, 6);
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.of(markerRow(day)));
            AtomicInteger callCount = new AtomicInteger();
            doAnswer(
                            inv -> {
                                callCount.incrementAndGet();
                                // 실행 중 재진입 시뮬레이션 — running=true 상태에서 스케줄러가 다시 발화됨
                                scheduler.run();
                                return new ShortSaleT0RevisionCorrectionResult(0, 0);
                            })
                    .when(t0RevisionCorrectionService)
                    .correctT0Revisions(day);

            scheduler.run();

            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("예외 발생 후에도 running 플래그가 해제되어 다음 실행이 정상 동작한다")
        void afterException_flagResetAllowsNextRun() {
            LocalDate day = LocalDate.of(2026, 8, 6);
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.of(markerRow(day)));
            doThrow(new RuntimeException("transient"))
                    .when(t0RevisionCorrectionService)
                    .correctT0Revisions(day);
            scheduler.run();

            Mockito.reset(t0RevisionCorrectionService);
            scheduler.run();

            verify(t0RevisionCorrectionService).correctT0Revisions(day);
        }
    }
}
