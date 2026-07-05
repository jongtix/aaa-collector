package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.rights.OverseasSplitCollectionService.OverseasSplitCollectionResult;
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

/**
 * {@link OverseasSplitScheduler} 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-002).
 *
 * <p>평일 17:00 ET cron·fixedDelay 미사용·개장일 1회 수집·예외 흡수·ET zone 환산을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OverseasSplitScheduler 단위 테스트")
class OverseasSplitSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private OverseasSplitCollectionService collectionService;

    private OverseasSplitScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OverseasSplitScheduler(collectionService, Clock.system(KST));
    }

    private OverseasSplitCollectionResult result() {
        return new OverseasSplitCollectionResult(2, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-OSPLIT-002)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 17 * * MON-FRI', zone='America/New_York'")
        void collectSplits_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = OverseasSplitScheduler.class.getMethod("collectSplits");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 17 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 (ADR-008)")
        void collectSplits_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = OverseasSplitScheduler.class.getMethod("collectSplits");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectSplits — 수집 흐름 (AC-11)")
    class CollectFlow {

        @Test
        @DisplayName("AC-11: 진입점 발화 — collect 정확히 1회 호출")
        void collectSplits_callsCollectExactlyOnce() {
            when(collectionService.collect()).thenReturn(result());

            scheduler.collectSplits();

            verify(collectionService, times(1)).collect();
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 미종료")
        void collectSplits_exceptionAbsorbed() {
            when(collectionService.collect()).thenThrow(new RuntimeException("수집 예외"));

            assertThatCode(scheduler::collectSplits).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ET zone 가드 (REQ-OSPLIT-002)")
    class EtZoneGuard {

        @Test
        @DisplayName("cron 17:00 ET 발화 Instant(=KST 익일) — 예외 없이 collect 호출")
        void collectSplits_usesEtZone_firesCollect() {
            Instant firingInstant = ZonedDateTime.of(2026, 6, 18, 17, 0, 0, 0, ET).toInstant();
            LocalDate etDate = LocalDate.of(2026, 6, 18);
            LocalDate kstDate = LocalDate.ofInstant(firingInstant, KST);
            assertThat(kstDate).isNotEqualTo(etDate);

            Clock fixedClock = Clock.fixed(firingInstant, ET);
            OverseasSplitScheduler schedulerUnderTest =
                    new OverseasSplitScheduler(collectionService, fixedClock);
            when(collectionService.collect()).thenReturn(result());

            assertThatCode(schedulerUnderTest::collectSplits).doesNotThrowAnyException();
            verify(collectionService, times(1)).collect();
        }
    }
}
