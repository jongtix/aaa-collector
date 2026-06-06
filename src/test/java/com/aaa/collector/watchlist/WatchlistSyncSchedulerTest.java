package com.aaa.collector.watchlist;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
