package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@link ShortSaleDomesticCorrectionScheduler} 단위 테스트
 * (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-030~032, REQ-T0R-043~045, plan.md §M7).
 *
 * <p>{@code FinraCdnShortSaleBackfillSchedulerTest} 구조(cron 애너테이션 검증 + single-flight + 예외 흡수)를
 * 답습하고, 이 스케줄러 고유의 Track1→Track2 순서·T0R 게이트 조회·전달을 추가로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShortSaleDomesticCorrectionScheduler")
class ShortSaleDomesticCorrectionSchedulerTest {

    @Mock private ShortSaleVolRateCorrectionService correctionService;
    @Mock private T0rCorrectionStatusRepository t0rCorrectionStatusRepository;

    private ShortSaleDomesticCorrectionScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler =
                new ShortSaleDomesticCorrectionScheduler(
                        correctionService, t0rCorrectionStatusRepository);
    }

    private ShortSaleVolRateCorrectionResult emptyResult() {
        return new ShortSaleVolRateCorrectionResult(0, 0, 0);
    }

    @Nested
    @DisplayName("@Scheduled cron 전용 (REQ-SSVC-030)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("run 메서드에 전용 cron 프로퍼티(기본 0 0 20 * * *, Asia/Seoul) 존재")
        void run_hasCronAnnotationWithDefaults() throws NoSuchMethodException {
            Method method = ShortSaleDomesticCorrectionScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron())
                    .isEqualTo("${aaa.shortsale-domestic.correction.cron:0 0 20 * * *}");
            assertThat(scheduled.zone())
                    .isEqualTo("${aaa.shortsale-domestic.correction.zone:Asia/Seoul}");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate는 사용하지 않는다(cron 전용 정적 확인)")
        void run_neverUsesFixedDelayOrFixedRate() throws NoSuchMethodException {
            Method method = ShortSaleDomesticCorrectionScheduler.class.getMethod("run");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRate()).isEqualTo(-1);
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Track1→Track2 순차 위임 (REQ-SSVC-031, -034)")
    class TrackDelegationOrder {

        @Test
        @DisplayName("완료 마커 NULL — 게이트 active=true로 Track1 먼저, Track2 그 다음 호출")
        void gateActive_track1BeforeTrack2WithSameGate() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(
                            Optional.of(
                                    new T0rCorrectionStatus(
                                            T0rCorrectionStatus.SINGLETON_ID,
                                            LocalDate.of(2026, 8, 6),
                                            null)));
            T0rGateState expectedGate = new T0rGateState(true, LocalDate.of(2026, 8, 6));
            when(correctionService.correctLegacyBacklog(expectedGate)).thenReturn(emptyResult());
            when(correctionService.verifyRecentInserts(expectedGate)).thenReturn(emptyResult());

            scheduler.run();

            InOrder inOrder = Mockito.inOrder(correctionService);
            inOrder.verify(correctionService).correctLegacyBacklog(expectedGate);
            inOrder.verify(correctionService).verifyRecentInserts(expectedGate);
        }

        @Test
        @DisplayName("완료 마커 NOT NULL — 게이트 active=false로 양쪽 트랙 호출")
        void gateCompleted_bothTracksReceiveInactiveGate() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(
                            Optional.of(
                                    new T0rCorrectionStatus(
                                            T0rCorrectionStatus.SINGLETON_ID,
                                            LocalDate.of(2026, 8, 6),
                                            LocalDateTime.of(2026, 8, 10, 9, 0))));
            T0rGateState expectedGate = new T0rGateState(false, LocalDate.of(2026, 8, 6));
            when(correctionService.correctLegacyBacklog(expectedGate)).thenReturn(emptyResult());
            when(correctionService.verifyRecentInserts(expectedGate)).thenReturn(emptyResult());

            scheduler.run();

            verify(correctionService).correctLegacyBacklog(expectedGate);
            verify(correctionService).verifyRecentInserts(expectedGate);
        }

        @Test
        @DisplayName("t0r_correction_status 행 없음(방어적 fallback) — 게이트 비활성으로 양쪽 트랙 그대로 실행")
        void markerRowMissing_fallsBackToInactiveGate() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.empty());
            when(correctionService.correctLegacyBacklog(eq(T0rGateState.inactive())))
                    .thenReturn(emptyResult());
            when(correctionService.verifyRecentInserts(eq(T0rGateState.inactive())))
                    .thenReturn(emptyResult());

            assertThatCode(scheduler::run).doesNotThrowAnyException();

            verify(correctionService).correctLegacyBacklog(T0rGateState.inactive());
            verify(correctionService).verifyRecentInserts(T0rGateState.inactive());
        }
    }

    @Nested
    @DisplayName("예외 흡수")
    class ExceptionAbsorption {

        @Test
        @DisplayName("Track1 예외 — 스케줄러 스레드로 전파되지 않고 Track2는 호출되지 않는다")
        void track1Exception_doesNotPropagateAndSkipsTrack2() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.empty());
            doThrow(new RuntimeException("db error"))
                    .when(correctionService)
                    .correctLegacyBacklog(T0rGateState.inactive());

            assertThatCode(scheduler::run).doesNotThrowAnyException();

            verify(correctionService, never()).verifyRecentInserts(T0rGateState.inactive());
        }
    }

    @Nested
    @DisplayName("AtomicBoolean single-flight 가드 (독립성)")
    class SingleFlightGuard {

        @Test
        @DisplayName("실행 중(running=true) 재진입 시 정정 서비스를 다시 호출하지 않는다")
        void reentrantCallWhileRunning_skipsCorrectionInvocation() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.empty());
            AtomicInteger callCount = new AtomicInteger();
            doAnswer(
                            inv -> {
                                callCount.incrementAndGet();
                                // 실행 중 재진입 시뮬레이션 — running=true 상태에서 스케줄러가 다시 발화됨
                                scheduler.run();
                                return emptyResult();
                            })
                    .when(correctionService)
                    .correctLegacyBacklog(T0rGateState.inactive());
            when(correctionService.verifyRecentInserts(T0rGateState.inactive()))
                    .thenReturn(emptyResult());

            scheduler.run();

            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("예외 발생 후에도 running 플래그가 해제되어 다음 실행이 정상 동작한다")
        void afterException_flagResetAllowsNextRun() {
            when(t0rCorrectionStatusRepository.findById(T0rCorrectionStatus.SINGLETON_ID))
                    .thenReturn(Optional.empty());
            doThrow(new RuntimeException("transient"))
                    .when(correctionService)
                    .correctLegacyBacklog(T0rGateState.inactive());
            scheduler.run();

            Mockito.reset(correctionService);
            when(correctionService.correctLegacyBacklog(T0rGateState.inactive()))
                    .thenReturn(emptyResult());
            when(correctionService.verifyRecentInserts(T0rGateState.inactive()))
                    .thenReturn(emptyResult());
            scheduler.run();

            verify(correctionService).correctLegacyBacklog(T0rGateState.inactive());
            verify(correctionService).verifyRecentInserts(T0rGateState.inactive());
        }
    }
}
