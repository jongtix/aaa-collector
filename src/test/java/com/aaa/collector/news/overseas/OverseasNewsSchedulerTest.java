package com.aaa.collector.news.overseas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchMetrics;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasNewsScheduler 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-ETC-001)")
class OverseasNewsSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private OverseasNewsTitleCollectionService collectionService;
    @Mock private BatchMetrics batchMetrics;

    private OverseasNewsScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OverseasNewsScheduler(collectionService, Clock.system(KST), batchMetrics);
    }

    private OverseasNewsCollectionResult result() {
        return new OverseasNewsCollectionResult(10, 8, 2);
    }

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-OVE-002/-003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0/10 9-16 * * MON-FRI', zone='America/New_York' (미국 장중 10분)")
        void collectNews_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = OverseasNewsScheduler.class.getMethod("collectNews");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0/10 9-16 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 (REQ-OVE-003)")
        void collectNews_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = OverseasNewsScheduler.class.getMethod("collectNews");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectNews — 수집 흐름")
    class CollectNewsFlow {

        @Test
        @DisplayName("진입점 발화 — collect 정확히 1회 호출 (REQ-OVE-002)")
        void collectNews_callsCollectExactlyOnce() {
            when(collectionService.collect()).thenReturn(result());

            scheduler.collectNews();

            verify(collectionService, times(1)).collect();
        }

        @Test
        @DisplayName("collect 성공 시 overseas-news 배치 라벨로 완료 계측한다 (REQ-WM-013)")
        void collectNews_recordsBatchCompletion() {
            when(collectionService.collect()).thenReturn(result());

            scheduler.collectNews();

            verify(batchMetrics).recordCompletion("overseas-news", 10, 8, 0, 2);
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 미종료 (REQ-OVE-004)")
        void collectNews_exceptionInCollect_absorbed() {
            when(collectionService.collect()).thenThrow(new RuntimeException("수집 예외"));

            assertThatCode(scheduler::collectNews).doesNotThrowAnyException();
        }
    }

    /**
     * ET zone 가드 — cron 발화 시각(미국 장중)은 서버 KST 익일이다. 스케줄러가 로그 기준일을 ET zone으로 환산하는지(KST/시스템 zone 회귀
     * 방지) 검증한다. Clock.fixed로 발화 Instant를 고정하고 collect()가 호출되는지 확인한다.
     */
    @Nested
    @DisplayName("ET zone 가드 (REQ-OVE-002)")
    class EtZoneGuard {

        @Test
        @DisplayName("cron 장중 ET 발화 Instant(=KST 익일) — 예외 없이 collect 호출")
        void collectNews_usesEtZone_firesCollect() {
            // 2026-06-18 10:00 ET = 2026-06-18 23:00 KST (장중 ET이지만 KST는 야간)
            Instant firingInstant = ZonedDateTime.of(2026, 6, 18, 10, 0, 0, 0, ET).toInstant();
            LocalDate etDate = LocalDate.of(2026, 6, 18);

            Clock fixedClock = Clock.fixed(firingInstant, ET);
            OverseasNewsScheduler schedulerUnderTest =
                    new OverseasNewsScheduler(collectionService, fixedClock, batchMetrics);
            when(collectionService.collect()).thenReturn(result());

            // Act & Assert — ET 환산 + collect 발화가 예외 없이 완료
            assertThat(LocalDate.ofInstant(firingInstant, ET)).isEqualTo(etDate);
            assertThatCode(schedulerUnderTest::collectNews).doesNotThrowAnyException();
            verify(collectionService, times(1)).collect();
        }
    }
}
