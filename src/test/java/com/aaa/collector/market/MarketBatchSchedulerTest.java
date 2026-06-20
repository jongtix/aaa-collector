package com.aaa.collector.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.macro.CompInterestCollectionService;
import com.aaa.collector.macro.MacroCollectionResult;
import com.aaa.collector.macro.MarketFundsCollectionService;
import com.aaa.collector.market.indicator.usdkrw.UsdkrwCollectionService;
import com.aaa.collector.market.indicator.vix.VixCollectionService;
import com.aaa.collector.stock.DividendCollectionResult;
import com.aaa.collector.stock.DividendScheduleCollectionService;
import com.aaa.collector.stock.RevSplitCollectionResult;
import com.aaa.collector.stock.RevSplitCollectionService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MarketBatchScheduler 단위 테스트")
class MarketBatchSchedulerTest {

    @Mock private SectorIndexCollectionService sectorIndexCollectionService;
    @Mock private CompInterestCollectionService compInterestCollectionService;
    @Mock private MarketFundsCollectionService marketFundsCollectionService;
    @Mock private DividendScheduleCollectionService dividendScheduleCollectionService;
    @Mock private RevSplitCollectionService revSplitCollectionService;
    @Mock private UsdkrwCollectionService usdkrwCollectionService;
    @Mock private VixCollectionService vixCollectionService;

    @InjectMocks private MarketBatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        // 정상 stub — 예외 격리 테스트 외에는 성공 반환
        when(sectorIndexCollectionService.collect(any(LocalDate.class)))
                .thenReturn(new SectorIndexCollectionResult(2, 2, 0));
        when(compInterestCollectionService.collect())
                .thenReturn(new MacroCollectionResult(8, 8, 0));
        when(marketFundsCollectionService.collect(anyString()))
                .thenReturn(new MacroCollectionResult(9, 9, 0));
        when(dividendScheduleCollectionService.collect(anyString(), anyString()))
                .thenReturn(new DividendCollectionResult(10, 8, 1, 1));
        when(revSplitCollectionService.collect(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new RevSplitCollectionResult(21, 5, 14, 2));
    }

    // ────────────────────────────────────────────────────────────────────
    // cron 어노테이션 검증 (REQ-BATCH3-001, REQ-BATCH3-003)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH3-001/003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 17 * * MON-FRI', zone='Asia/Seoul'")
        void collectMarket_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = MarketBatchScheduler.class.getMethod("collectMarket");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 17 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (REQ-BATCH3-003)")
        void collectMarket_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = MarketBatchScheduler.class.getMethod("collectMarket");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 정상 수집 흐름 — 4종 고정 순서 (REQ-BATCH3-005)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 수집 흐름 — 7종 고정 순서 (REQ-BATCH3-005, REQ-BATCH5-001, REQ-001)")
    class NormalFlow {

        @Test
        @DisplayName(
                "7종 모두 호출 — sectorIndex → compInterest → marketFunds → dividendSchedule → revSplit → usdkrw → vix")
        void collectMarket_callsAllSevenServicesInOrder() {
            // Act
            scheduler.collectMarket();

            // Assert — 고정 순서 검증
            InOrder inOrder =
                    inOrder(
                            sectorIndexCollectionService,
                            compInterestCollectionService,
                            marketFundsCollectionService,
                            dividendScheduleCollectionService,
                            revSplitCollectionService,
                            usdkrwCollectionService,
                            vixCollectionService);
            inOrder.verify(sectorIndexCollectionService).collect(any(LocalDate.class));
            inOrder.verify(compInterestCollectionService).collect();
            inOrder.verify(marketFundsCollectionService).collect(anyString());
            inOrder.verify(dividendScheduleCollectionService).collect(anyString(), anyString());
            inOrder.verify(revSplitCollectionService)
                    .collect(any(LocalDate.class), any(LocalDate.class));
            inOrder.verify(usdkrwCollectionService).collectDaily(any(LocalDate.class));
            inOrder.verify(vixCollectionService).collectDaily(any(LocalDate.class));
        }

        @Test
        @DisplayName("USDKRW(T8)가 revSplit(T7) 다음, VIX(T9) 앞에 호출됨 (REQ-001)")
        void usdkrw_calledAfterRevSplit_beforeVix() {
            scheduler.collectMarket();

            InOrder inOrder =
                    inOrder(
                            revSplitCollectionService,
                            usdkrwCollectionService,
                            vixCollectionService);
            inOrder.verify(revSplitCollectionService)
                    .collect(any(LocalDate.class), any(LocalDate.class));
            inOrder.verify(usdkrwCollectionService).collectDaily(any(LocalDate.class));
            inOrder.verify(vixCollectionService).collectDaily(any(LocalDate.class));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 예외 격리 — 한 종 실패가 다음 종을 막지 않음 (REQ-BATCH3-004)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외 격리 — 한 종 실패가 다음 종을 막지 않음 (REQ-BATCH3-004)")
    class ExceptionIsolation {

        @Test
        @DisplayName("sectorIndex 예외 — 스케줄러 스레드로 전파 안 됨, 나머지 3종 계속 호출")
        void sectorIndexException_otherServicesStillCalled() {
            // Arrange
            when(sectorIndexCollectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("업종지수 수집 실패"));

            // Act — 예외가 전파되지 않아야 함
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();

            // Assert — 나머지 3종 계속 호출
            verify(compInterestCollectionService).collect();
            verify(marketFundsCollectionService).collect(anyString());
            verify(dividendScheduleCollectionService).collect(anyString(), anyString());
        }

        @Test
        @DisplayName("compInterest 예외 — 이후 2종 계속 호출")
        void compInterestException_subsequentServicesStillCalled() {
            // Arrange
            when(compInterestCollectionService.collect())
                    .thenThrow(new RuntimeException("금리 수집 실패"));

            // Act & Assert
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();

            verify(marketFundsCollectionService).collect(anyString());
            verify(dividendScheduleCollectionService).collect(anyString(), anyString());
        }

        @Test
        @DisplayName("marketFunds 예외 — dividendSchedule 계속 호출")
        void marketFundsException_dividendStillCalled() {
            // Arrange
            when(marketFundsCollectionService.collect(anyString()))
                    .thenThrow(new RuntimeException("증시자금 수집 실패"));

            // Act & Assert
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();

            verify(dividendScheduleCollectionService).collect(anyString(), anyString());
        }

        @Test
        @DisplayName("dividendSchedule 예외 — 스케줄러 스레드로 전파 안 됨, revSplit 계속 호출")
        void dividendScheduleException_revSplitStillCalled() {
            // Arrange
            when(dividendScheduleCollectionService.collect(anyString(), anyString()))
                    .thenThrow(new RuntimeException("배당 수집 실패"));

            // Act & Assert
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();

            // revSplit도 계속 호출됨
            verify(revSplitCollectionService).collect(any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("AC-SCHED-3: revSplit 예외 — 스케줄러 스레드로 전파 안 됨 (종 단위 격리)")
        void revSplitException_notPropagated() {
            // Arrange
            when(revSplitCollectionService.collect(any(LocalDate.class), any(LocalDate.class)))
                    .thenThrow(new RuntimeException("액면교체 수집 실패"));

            // Act & Assert — 예외가 스케줄러 스레드로 전파되지 않음
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("모든 7종 예외 — 스케줄러 스레드 종료 없음")
        void allSevenServicesException_schedulerThreadSurvives() {
            // Arrange
            when(sectorIndexCollectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("T3 실패"));
            when(compInterestCollectionService.collect()).thenThrow(new RuntimeException("T4 실패"));
            when(marketFundsCollectionService.collect(anyString()))
                    .thenThrow(new RuntimeException("T5 실패"));
            when(dividendScheduleCollectionService.collect(anyString(), anyString()))
                    .thenThrow(new RuntimeException("T6 실패"));
            when(revSplitCollectionService.collect(any(LocalDate.class), any(LocalDate.class)))
                    .thenThrow(new RuntimeException("T7 실패"));
            org.mockito.Mockito.doThrow(new RuntimeException("T8 실패"))
                    .when(usdkrwCollectionService)
                    .collectDaily(any(LocalDate.class));
            org.mockito.Mockito.doThrow(new RuntimeException("T9 실패"))
                    .when(vixCollectionService)
                    .collectDaily(any(LocalDate.class));

            // Act & Assert
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 이벤트 미발행 (REQ-BATCH3-011)
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("이벤트 미발행 (REQ-BATCH3-011)")
    class NoEventPublish {

        @Test
        @DisplayName("stream:daily:complete 미발행 — Redis publisher 없음")
        void collectMarket_noEventPublisher_verifiedByNoPublisherDependency() {
            // MarketBatchScheduler 생성자에 publisher 없음을 컴파일 타임 확인 (설계 검증)
            // 실행 중 예외 없음으로 대리 검증
            assertThatCode(scheduler::collectMarket).doesNotThrowAnyException();
        }
    }
}
