package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.stock.grade.GradeClassificationService;
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

/**
 * WatchlistSyncScheduler 단위 테스트 (SPEC-COLLECTOR-GRADE-003 Task 5).
 *
 * <p>검증 범위:
 *
 * <ul>
 *   <li>08:20 KST sync + classifyDomestic() 결합 (REQ-WLSYNC-111 보존)
 *   <li>15:45 syncAfternoon 제거 (REQ-WLSYNC-112 supersede)
 *   <li>US ET 08:50 전체 sync + classifyOverseas() 신설 (REQ-GRADE-005/M5)
 *   <li>슬롯별 독립 single-flight 가드 (REQ-GRADE-007)
 *   <li>@Scheduled cron/zone 단언
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistSyncScheduler 단위 테스트 (Task 5)")
class WatchlistSyncSchedulerTest {

    @Mock private WatchlistSyncService watchlistSyncService;
    @Mock private GradeClassificationService gradeClassificationService;
    @InjectMocks private WatchlistSyncScheduler scheduler;

    // -----------------------------------------------------------------------
    // @Scheduled cron/zone 단언
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("@Scheduled cron/zone 단언")
    class CronSchedule {

        @Test
        @DisplayName("syncMorning cron='0 20 8 * * *', zone='Asia/Seoul' (REQ-WLSYNC-111)")
        void syncMorning_cronIsEightTwentyKst() throws NoSuchMethodException {
            Method method = WatchlistSyncScheduler.class.getMethod("syncMorning");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 20 8 * * *");
            assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("syncUs cron='0 50 8 * * *', zone='America/New_York' (REQ-GRADE-005/M2)")
        void syncUs_cronIsEightFiftyEt() throws NoSuchMethodException {
            Method method = WatchlistSyncScheduler.class.getMethod("syncUs");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);

            assertThat(scheduled).isNotNull();
            assertThat(scheduled.cron()).isEqualTo("0 50 8 * * *");
            assertThat(scheduled.zone()).isEqualTo("America/New_York");
        }

        @Test
        @DisplayName("syncAfternoon 메서드 없음 (REQ-WLSYNC-112 supersede)")
        void syncAfternoon_methodDoesNotExist() {
            final String afternoonMethodName = "syncAfternoon";
            boolean hasSyncAfternoon = false;
            for (Method method : WatchlistSyncScheduler.class.getMethods()) {
                if (afternoonMethodName.equals(method.getName())) {
                    hasSyncAfternoon = true;
                    break;
                }
            }
            assertThat(hasSyncAfternoon).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // syncMorning — 08:20 KST: sync → classifyDomestic (REQ-WLSYNC-111)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("syncMorning — KRX 08:20 KST sync + classifyDomestic")
    class SyncMorning {

        @Test
        @DisplayName("정상 실행 — sync() 1회 + classifyDomestic() 1회 순차 호출")
        void syncMorning_normal_syncThenClassifyDomestic() {
            scheduler.syncMorning();

            verify(watchlistSyncService).sync();
            verify(gradeClassificationService).classifyDomestic();
        }

        @Test
        @DisplayName("syncMorning()은 classifyOverseas() 미호출 (KRX 전용)")
        void syncMorning_doesNotCallClassifyOverseas() {
            scheduler.syncMorning();

            verify(gradeClassificationService, never()).classifyOverseas();
        }

        @Test
        @DisplayName("morningRunning=true 시 sync/classify 모두 미호출 (single-flight 가드)")
        void syncMorning_skipsWhenRunning() {
            ReflectionTestUtils.setField(scheduler, "morningRunning", new AtomicBoolean(true));

            scheduler.syncMorning();

            verify(watchlistSyncService, never()).sync();
            verify(gradeClassificationService, never()).classifyDomestic();
        }

        @Test
        @DisplayName("정상 완료 후 가드 해제 — 다음 실행 가능")
        void syncMorning_releasesGuardOnCompletion() {
            scheduler.syncMorning();
            scheduler.syncMorning();

            verify(watchlistSyncService, times(2)).sync();
        }

        @Test
        @DisplayName("sync() 예외 후 가드 해제 — 다음 실행 가능")
        void syncMorning_releasesGuardOnSyncException() {
            // Arrange
            doThrow(new RuntimeException("sync 실패")).doNothing().when(watchlistSyncService).sync();

            // Act & Assert
            scheduler.syncMorning();
            scheduler.syncMorning();

            verify(watchlistSyncService, times(2)).sync();
        }

        @Test
        @DisplayName("classifyDomestic() 예외 후 가드 해제 — 다음 실행 가능")
        void syncMorning_releasesGuardOnClassifyException() {
            // Arrange
            doThrow(new RuntimeException("classify 실패"))
                    .doNothing()
                    .when(gradeClassificationService)
                    .classifyDomestic();

            // Act & Assert
            scheduler.syncMorning();
            scheduler.syncMorning();

            verify(gradeClassificationService, times(2)).classifyDomestic();
        }
    }

    // -----------------------------------------------------------------------
    // syncUs — US ET 08:50: 전체 sync → classifyOverseas (REQ-GRADE-005/M5)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("syncUs — US ET 08:50 전체 sync + classifyOverseas")
    class SyncUs {

        @Test
        @DisplayName("정상 실행 — sync() 1회 + classifyOverseas() 1회 순차 호출")
        void syncUs_normal_syncThenClassifyOverseas() {
            scheduler.syncUs();

            verify(watchlistSyncService).sync();
            verify(gradeClassificationService).classifyOverseas();
        }

        @Test
        @DisplayName("syncUs()는 classifyDomestic() 미호출 (US 전용)")
        void syncUs_doesNotCallClassifyDomestic() {
            scheduler.syncUs();

            verify(gradeClassificationService, never()).classifyDomestic();
        }

        @Test
        @DisplayName("usRunning=true 시 sync/classify 모두 미호출 (single-flight 가드)")
        void syncUs_skipsWhenRunning() {
            ReflectionTestUtils.setField(scheduler, "usRunning", new AtomicBoolean(true));

            scheduler.syncUs();

            verify(watchlistSyncService, never()).sync();
            verify(gradeClassificationService, never()).classifyOverseas();
        }

        @Test
        @DisplayName("정상 완료 후 가드 해제 — 다음 실행 가능")
        void syncUs_releasesGuardOnCompletion() {
            scheduler.syncUs();
            scheduler.syncUs();

            verify(watchlistSyncService, times(2)).sync();
        }

        @Test
        @DisplayName("sync() 예외 후 가드 해제 — 다음 실행 가능")
        void syncUs_releasesGuardOnException() {
            // Arrange
            doThrow(new RuntimeException("US sync 실패"))
                    .doNothing()
                    .when(watchlistSyncService)
                    .sync();

            // Act & Assert
            scheduler.syncUs();
            scheduler.syncUs();

            verify(watchlistSyncService, times(2)).sync();
        }
    }

    // -----------------------------------------------------------------------
    // 슬롯 독립 가드 (REQ-GRADE-007)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("슬롯 독립 가드 — morningRunning/usRunning 상호 독립")
    class IndependentGuard {

        @Test
        @DisplayName("morningRunning=true 상태에서 syncUs() 정상 실행 — US 가드는 별개")
        void syncUs_notBlockedByMorningGuard() {
            ReflectionTestUtils.setField(scheduler, "morningRunning", new AtomicBoolean(true));

            scheduler.syncUs();

            // morningRunning이 true여도 syncUs는 실행 가능
            verify(watchlistSyncService).sync();
            verify(gradeClassificationService).classifyOverseas();
        }

        @Test
        @DisplayName("usRunning=true 상태에서 syncMorning() 정상 실행 — KRX 가드는 별개")
        void syncMorning_notBlockedByUsGuard() {
            ReflectionTestUtils.setField(scheduler, "usRunning", new AtomicBoolean(true));

            scheduler.syncMorning();

            // usRunning이 true여도 syncMorning은 실행 가능
            verify(watchlistSyncService).sync();
            verify(gradeClassificationService).classifyDomestic();
        }
    }
}
