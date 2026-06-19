package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import com.aaa.collector.stock.supply.DomesticSupplyDemandCollectionService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("DomesticDailyOhlcvScheduler 단위 테스트")
class DomesticDailyOhlcvSchedulerTest {

    @Mock private DomesticDailyOhlcvCollectionService collectionService;
    @Mock private DailyCompletePublisher publisher;
    @Mock private DomesticSupplyDemandCollectionService supplyDemandService;

    @InjectMocks private DomesticDailyOhlcvScheduler scheduler;

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-BATCH-050, REQ-BATCH-051)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 16 * * MON-FRI', zone='Asia/Seoul' (AC-6 S6-1, S6-2)")
        void collectDaily_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = DomesticDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 16 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 사용 (REQ-BATCH-051, AC-6 S6-2)")
        void collectDaily_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = DomesticDailyOhlcvScheduler.class.getMethod("collectDaily");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectDaily — 수집 흐름 (회귀: 일봉 수집·발행 보존)")
    class CollectDailyFlow {

        @Test
        @DisplayName("정상 흐름 — collect + publish + 수급 호출")
        void collectDaily_normalFlow_collectsAndPublishes() {
            // Arrange
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            // Act
            scheduler.collectDaily();

            // Assert
            verify(collectionService).collect(any(LocalDate.class));
            verify(publisher).publish(result, "domestic");
            verify(supplyDemandService).collectAll(any(LocalDate.class));
        }

        @Test
        @DisplayName("collect에서 예외 — 예외 흡수, publish 미호출, 그래도 수급은 트리거 (REQ-002)")
        void collectDaily_exceptionInCollect_absorbedNoPropagation() {
            // Arrange
            Mockito.when(collectionService.collect(any(LocalDate.class)))
                    .thenThrow(new RuntimeException("수집 중 예외"));

            // Act — 예외가 전파되지 않아야 함
            assertThatCode(scheduler::collectDaily).doesNotThrowAnyException();

            // Assert — 일봉 실패가 수급을 막지 않음 (단계 간 독립)
            verify(supplyDemandService).collectAll(any(LocalDate.class));
        }
    }

    @Nested
    @DisplayName("collectDaily — 수급 인라인 체인 (AC-1 S1-1/S1-2/S1-3)")
    class SupplyInlineChain {

        @Test
        @DisplayName("일봉 발행 완료 후 수급 collectAll 1회 호출 (발행 → 수급 순서, S1-1)")
        void supplyCalledOnceAfterPublish() {
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);

            scheduler.collectDaily();

            // 발행 후 수급 호출 순서 검증
            InOrder inOrder = inOrder(publisher, supplyDemandService);
            inOrder.verify(publisher).publish(result, "domestic");
            inOrder.verify(supplyDemandService).collectAll(any(LocalDate.class));
        }

        @Test
        @DisplayName("수급 collectAll 예외 — 스케줄러 스레드로 전파되지 않음 (S1-3, REQ-003)")
        void supplyException_notPropagated() {
            CollectionResult result = new CollectionResult(5, 5, 0);
            Mockito.when(collectionService.collect(any(LocalDate.class))).thenReturn(result);
            Mockito.doThrow(new RuntimeException("수급 수집 실패"))
                    .when(supplyDemandService)
                    .collectAll(any(LocalDate.class));

            assertThatCode(scheduler::collectDaily).doesNotThrowAnyException();
        }
    }
}
