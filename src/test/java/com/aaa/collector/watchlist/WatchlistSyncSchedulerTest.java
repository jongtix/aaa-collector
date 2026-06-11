package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WatchlistSyncSchedulerTest {

    @Mock private WatchlistSyncService watchlistSyncService;
    @InjectMocks private WatchlistSyncScheduler scheduler;

    @Nested
    @DisplayName("syncMorning — 동시 실행 가드")
    class SyncMorning {

        @Test
        @DisplayName("sync 실행 중 재진입 시 sync()를 호출하지 않는다")
        void skipsWhenRunning() {
            ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

            scheduler.syncMorning();

            verify(watchlistSyncService, never()).sync();
        }

        @Test
        @DisplayName("정상 완료 후 가드가 해제되어 다음 실행이 가능하다")
        void releasesGuardOnCompletion() {
            scheduler.syncMorning();
            scheduler.syncMorning();

            verify(watchlistSyncService, times(2)).sync();
        }

        @Test
        @DisplayName("sync() 예외 발생 후 가드가 해제되어 다음 실행이 가능하다")
        void releasesGuardOnException() {
            // Arrange
            doThrow(new RuntimeException("test error"))
                    .doNothing()
                    .when(watchlistSyncService)
                    .sync();

            // Act & Assert
            scheduler.syncMorning();
            scheduler.syncMorning();

            verify(watchlistSyncService, times(2)).sync();
        }
    }

    @Nested
    @DisplayName("syncAfternoon — 동시 실행 가드")
    class SyncAfternoon {

        @Test
        @DisplayName("sync 실행 중 재진입 시 sync()를 호출하지 않는다")
        void skipsWhenRunning() {
            ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

            scheduler.syncAfternoon();

            verify(watchlistSyncService, never()).sync();
        }

        @Test
        @DisplayName("sync() 예외 발생 후 가드가 해제되어 다음 실행이 가능하다")
        void releasesGuardOnException() {
            // Arrange
            doThrow(new RuntimeException("test error"))
                    .doNothing()
                    .when(watchlistSyncService)
                    .sync();

            // Act & Assert
            scheduler.syncAfternoon();
            scheduler.syncAfternoon();

            verify(watchlistSyncService, times(2)).sync();
        }
    }

    @Nested
    @DisplayName("@Scheduled cron 설정")
    class CronSchedule {

        @Test
        @DisplayName("syncMorning cron은 '0 20 8 * * *'(08:20 매일)이고 zone은 'Asia/Seoul'이다 (AC-4)")
        void syncMorning_cronIsEightTwentyDaily() throws NoSuchMethodException {
            Method method = WatchlistSyncScheduler.class.getMethod("syncMorning");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 20 8 * * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("syncAfternoon cron은 '0 45 15 * * *'(15:45)로 변경 없이 유지된다 (AC-4)")
        void syncAfternoon_cronIsUnchanged() throws NoSuchMethodException {
            Method method = WatchlistSyncScheduler.class.getMethod("syncAfternoon");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 45 15 * * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }
    }

    @Nested
    @DisplayName("교차 트리거 가드")
    class CrossTrigger {

        @Test
        @DisplayName("syncMorning 실행 중 syncAfternoon 트리거 발생 시 차단된다")
        void syncAfternoonBlockedWhenMorningRunning() {
            ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

            scheduler.syncAfternoon();

            verify(watchlistSyncService, never()).sync();
        }

        @Test
        @DisplayName("syncAfternoon 실행 중 syncMorning 트리거 발생 시 차단된다")
        void syncMorningBlockedWhenAfternoonRunning() {
            ReflectionTestUtils.setField(scheduler, "running", new AtomicBoolean(true));

            scheduler.syncMorning();

            verify(watchlistSyncService, never()).sync();
        }
    }
}
