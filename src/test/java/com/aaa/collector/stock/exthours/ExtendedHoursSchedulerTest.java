package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExtendedHoursScheduler 단위 테스트")
class ExtendedHoursSchedulerTest {

    @Mock private ExtendedHoursCollectionService collectionService;

    private ExtendedHoursScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ExtendedHoursScheduler(collectionService);
    }

    @Nested
    @DisplayName("collectPre — PRE 세션 수집 발화")
    class CollectPre {

        @Test
        @DisplayName("collectPre 발화 → collect(PRE) 1회 호출")
        void collectPre_callsCollectWithPreSession() {
            scheduler.collectPre();

            verify(collectionService).collect(Session.PRE);
        }

        @Test
        @DisplayName("collectPre — collect(PRE) 예외 흡수, 스레드 생존")
        void collectPre_absorbsException() {
            doThrow(new RuntimeException("PRE 수집 오류")).when(collectionService).collect(Session.PRE);

            // Act & Assert — 예외 전파 없음
            assertThatCode(() -> scheduler.collectPre()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("collectAfter — AFTER 세션 수집 발화")
    class CollectAfter {

        @Test
        @DisplayName("collectAfter 발화 → collect(AFTER) 1회 호출")
        void collectAfter_callsCollectWithAfterSession() {
            scheduler.collectAfter();

            verify(collectionService).collect(Session.AFTER);
        }

        @Test
        @DisplayName("collectAfter — collect(AFTER) 예외 흡수, 스레드 생존")
        void collectAfter_absorbsException() {
            doThrow(new RuntimeException("AFTER 수집 오류"))
                    .when(collectionService)
                    .collect(Session.AFTER);

            // Act & Assert — 예외 전파 없음
            assertThatCode(() -> scheduler.collectAfter()).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("스케줄 방식 검증 — fixedDelay/fixedRate 미사용, zone=America/New_York")
    class ScheduleAnnotation {

        @Test
        @DisplayName("collectPre — cron만 사용, fixedDelay/fixedRate 없음, zone=America/New_York")
        void collectPre_usesCronOnlyWithEtZone() throws NoSuchMethodException {
            Method method = ExtendedHoursScheduler.class.getMethod("collectPre");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
            // fixedDelay/fixedRate 미사용 (미설정 시 -1)
            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("collectAfter — cron만 사용, fixedDelay/fixedRate 없음, zone=America/New_York")
        void collectAfter_usesCronOnlyWithEtZone() throws NoSuchMethodException {
            Method method = ExtendedHoursScheduler.class.getMethod("collectAfter");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
            assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
            assertThat(scheduled.fixedRate()).isEqualTo(-1L);
        }
    }
}
