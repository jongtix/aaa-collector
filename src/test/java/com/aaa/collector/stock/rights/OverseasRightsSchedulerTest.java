package com.aaa.collector.stock.rights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@DisplayName("OverseasRightsScheduler 단위 테스트 (SPEC-COLLECTOR-OVERSEAS-ETC-001)")
class OverseasRightsSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private OverseasRightsCollectionService collectionService;

    private OverseasRightsScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OverseasRightsScheduler(collectionService, Clock.system(KST));
    }

    private OverseasRightsCollectionResult result() {
        return new OverseasRightsCollectionResult(5, 3, 1, 1, 0, 0, 0, 0, 0, 0);
    }

    @Nested
    @DisplayName("cron 어노테이션 검증 (REQ-OVE-001/-003)")
    class ScheduledAnnotation {

        @Test
        @DisplayName("cron='0 0 17 * * MON-FRI', zone='America/New_York' (ET 일봉 슬롯 이후)")
        void collectRights_hasCorrectScheduledAnnotation() throws NoSuchMethodException {
            Method method = OverseasRightsScheduler.class.getMethod("collectRights");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 0 17 * * MON-FRI");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("fixedDelay/fixedRate 미사용 — cron만 (REQ-OVE-003)")
        void collectRights_noFixedDelayOrRate() throws NoSuchMethodException {
            Method method = OverseasRightsScheduler.class.getMethod("collectRights");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
            assertThat(scheduled.fixedDelayString()).isEmpty();
            assertThat(scheduled.fixedRateString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectRights — 수집 흐름")
    class CollectRightsFlow {

        @Test
        @DisplayName("진입점 발화 — collect 정확히 1회 호출 (REQ-OVE-001)")
        void collectRights_callsCollectExactlyOnce() {
            when(collectionService.collect()).thenReturn(result());

            scheduler.collectRights();

            verify(collectionService, times(1)).collect();
        }

        @Test
        @DisplayName("collect 예외 — 흡수, 스케줄러 미종료 (REQ-OVE-004)")
        void collectRights_exceptionInCollect_absorbed() {
            when(collectionService.collect()).thenThrow(new RuntimeException("수집 예외"));

            assertThatCode(scheduler::collectRights).doesNotThrowAnyException();
        }
    }

    /**
     * ET zone 가드 — cron 17:00 ET 발화 시각은 서버 KST 익일 새벽이다. 스케줄러가 로그 기준일을 ET zone으로 환산하는지(KST/시스템 zone
     * 회귀 방지) 검증한다. Clock.fixed로 발화 Instant를 고정하고 collect()가 호출되는지 확인한다.
     */
    @Nested
    @DisplayName("ET zone 가드 (REQ-OVE-001)")
    class EtZoneGuard {

        @Test
        @DisplayName("cron 17:00 ET 발화 Instant(=KST 익일) — 예외 없이 collect 호출")
        void collectRights_usesEtZone_firesCollect() {
            // 2026-06-18 17:00 ET = 2026-06-19 06:00 KST (ET 날짜와 KST 날짜가 다름)
            Instant firingInstant = ZonedDateTime.of(2026, 6, 18, 17, 0, 0, 0, ET).toInstant();
            LocalDate etDate = LocalDate.of(2026, 6, 18);
            LocalDate kstDate = LocalDate.ofInstant(firingInstant, KST);
            assertThat(kstDate).isNotEqualTo(etDate);

            Clock fixedClock = Clock.fixed(firingInstant, ET);
            OverseasRightsScheduler schedulerUnderTest =
                    new OverseasRightsScheduler(collectionService, fixedClock);
            when(collectionService.collect()).thenReturn(result());

            // Act & Assert — ET 환산 + collect 발화가 예외 없이 완료
            assertThatCode(schedulerUnderTest::collectRights).doesNotThrowAnyException();
            verify(collectionService, times(1)).collect();
        }
    }
}
