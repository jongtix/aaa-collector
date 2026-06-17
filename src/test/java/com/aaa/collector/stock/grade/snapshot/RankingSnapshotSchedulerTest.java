package com.aaa.collector.stock.grade.snapshot;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.kis.ranking.KisDomesticRankingClient;
import com.aaa.collector.kis.ranking.KisDomesticRankingResponse;
import com.aaa.collector.kis.ranking.KisOverseasRankingClient;
import com.aaa.collector.kis.ranking.KisOverseasRankingResponse;
import com.aaa.collector.stock.grade.AdtvPercentileCalculator;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RankingSnapshotScheduler 단위 테스트")
class RankingSnapshotSchedulerTest {

    @Mock private KisDomesticRankingClient domesticRankingClient;
    @Mock private KisOverseasRankingClient overseasRankingClient;
    @Mock private RankingSnapshotService snapshotService;
    @InjectMocks private RankingSnapshotScheduler scheduler;

    // -----------------------------------------------------------------------
    // @Scheduled cron / zone 단언 (시나리오 1-2 일부)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("@Scheduled cron / zone 설정 단언")
    class CronScheduleAnnotation {

        @Test
        @DisplayName("KRX 스냅샷 잡: cron '0 0 4 * * *', zone 'Asia/Seoul'")
        void snapshotKrx_cronAndZone() throws NoSuchMethodException {
            Method method = RankingSnapshotScheduler.class.getMethod("snapshotKrx");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            Assertions.assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
            Assertions.assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }

        @Test
        @DisplayName("US 스냅샷 잡: cron '0 0 16 * * *', zone 'Asia/Seoul' (KST-anchored, §Decision E)")
        void snapshotUs_cronAndZone() throws NoSuchMethodException {
            Method method = RankingSnapshotScheduler.class.getMethod("snapshotUs");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            Assertions.assertThat(scheduled.cron()).isEqualTo("0 0 16 * * *");
            Assertions.assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 1-1: KRX 04:00 잡 → KRX 순위 fetch → 저장
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1-1 — KRX 04:00 잡 → KRX 순위 fetch → 저장")
    class KrxSnapshotJob {

        @Test
        @DisplayName("정상 KRX 순위 → snapshotService.saveSnapshot('KRX', entries, clock) 호출")
        void snapshotKrx_normalRanking_savesSnapshot() {
            // Arrange: 04:00 KST로 시계 고정 (리셋 가드 통과)
            Instant at0400Kst = Instant.parse("2024-06-16T19:00:00Z"); // 04:00 KST
            ReflectionTestUtils.setField(
                    scheduler, "clock", Clock.fixed(at0400Kst, ZoneId.of("Asia/Seoul")));
            when(domesticRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisDomesticRankingResponse.RankedStock(
                                            "005930", "1", "삼성전자"),
                                    new KisDomesticRankingResponse.RankedStock(
                                            "000660", "2", "SK하이닉스")));

            // Act
            scheduler.snapshotKrx();

            // Assert
            ArgumentCaptor<List<AdtvPercentileCalculator.RankEntry>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(snapshotService).saveSnapshot(eq("KRX"), captor.capture(), any(Clock.class));
            Assertions.assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("fetch 공백 → saveSnapshot 엔트리 empty 리스트로 호출 (SKIP 판단은 service에서)")
        void snapshotKrx_emptyRanking_savesEmptyList() {
            // 04:00 KST로 시계 고정
            Instant at0400Kst = Instant.parse("2024-06-16T19:00:00Z");
            ReflectionTestUtils.setField(
                    scheduler, "clock", Clock.fixed(at0400Kst, ZoneId.of("Asia/Seoul")));
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of());

            scheduler.snapshotKrx();

            ArgumentCaptor<List<AdtvPercentileCalculator.RankEntry>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(snapshotService).saveSnapshot(eq("KRX"), captor.capture(), any(Clock.class));
            Assertions.assertThat(captor.getValue()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 1-2: US 16:00 KST 잡 → US 순위 fetch → 저장
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1-2 — US 16:00 KST 잡 → US 순위 fetch → 저장")
    class UsSnapshotJob {

        @Test
        @DisplayName("정상 US 순위 → snapshotService.saveSnapshot('US', entries, clock) 호출")
        void snapshotUs_normalRanking_savesSnapshot() {
            // Arrange
            when(overseasRankingClient.fetchRanking())
                    .thenReturn(
                            List.of(
                                    new KisOverseasRankingResponse.RankedStock("AAPL", "1"),
                                    new KisOverseasRankingResponse.RankedStock("MSFT", "2")));

            // Act
            scheduler.snapshotUs();

            // Assert
            ArgumentCaptor<List<AdtvPercentileCalculator.RankEntry>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(snapshotService).saveSnapshot(eq("US"), captor.capture(), any(Clock.class));
            Assertions.assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("fetch 공백 → saveSnapshot 엔트리 empty 리스트로 호출")
        void snapshotUs_emptyRanking_savesEmptyList() {
            when(overseasRankingClient.fetchRanking()).thenReturn(List.of());

            scheduler.snapshotUs();

            ArgumentCaptor<List<AdtvPercentileCalculator.RankEntry>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(snapshotService).saveSnapshot(eq("US"), captor.capture(), any(Clock.class));
            Assertions.assertThat(captor.getValue()).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 2-2: KRX 05:00 리셋 가드
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 2-2 — KRX 05:00 리셋 윈도우 넘는 fetch 금지")
    class KrxResetWindowGuard {

        @Test
        @DisplayName("시각이 05:00 KST 이후이면 fetch 미수행 및 saveSnapshot 미호출")
        void snapshotKrx_afterResetWindow_noFetchNoSave() {
            // Arrange: 시계를 05:01 KST로 설정 (리셋 윈도우 이후)
            // 2024-06-17 05:01 KST = 2024-06-16 20:01 UTC
            Instant past0501Kst = Instant.parse("2024-06-16T20:01:00Z");
            Clock clock = Clock.fixed(past0501Kst, ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(scheduler, "clock", clock);

            // Act
            scheduler.snapshotKrx();

            // Assert: fetch 및 save 미호출
            verify(domesticRankingClient, never()).fetchRanking();
            verify(snapshotService, never()).saveSnapshot(any(), any(), any());
        }

        @Test
        @DisplayName("시각이 04:59 KST이면 fetch 정상 수행")
        void snapshotKrx_beforeResetWindow_fetchProceeds() {
            // Arrange: 시계를 04:59 KST로 설정
            Instant before0500Kst = Instant.parse("2024-06-16T19:59:00Z");
            Clock clock = Clock.fixed(before0500Kst, ZoneId.of("Asia/Seoul"));
            ReflectionTestUtils.setField(scheduler, "clock", clock);
            when(domesticRankingClient.fetchRanking()).thenReturn(List.of());

            // Act
            scheduler.snapshotKrx();

            // Assert: fetch 호출됨
            verify(domesticRankingClient).fetchRanking();
        }
    }

    // -----------------------------------------------------------------------
    // REQ-GRADE-007: single-flight 가드
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("REQ-GRADE-007 — single-flight 가드")
    class SingleFlightGuard {

        @Test
        @DisplayName("KRX 잡 진행 중 재트리거 → 두 번째 fetch 미호출, 경고")
        void snapshotKrx_running_secondTriggerSkipped() {
            ReflectionTestUtils.setField(scheduler, "krxRunning", new AtomicBoolean(true));

            scheduler.snapshotKrx();

            verify(domesticRankingClient, never()).fetchRanking();
        }

        @Test
        @DisplayName("US 잡 진행 중 재트리거 → 두 번째 fetch 미호출")
        void snapshotUs_running_secondTriggerSkipped() {
            ReflectionTestUtils.setField(scheduler, "usRunning", new AtomicBoolean(true));

            scheduler.snapshotUs();

            verify(overseasRankingClient, never()).fetchRanking();
        }

        @Test
        @DisplayName("KRX 잡 예외 종료 후 가드 reset → 다음 cycle 정상 실행")
        void snapshotKrx_exceptionThenReset_nextCycleRuns() {
            // Arrange: 04:00 KST로 시계 고정
            Instant at0400Kst = Instant.parse("2024-06-16T19:00:00Z");
            ReflectionTestUtils.setField(
                    scheduler, "clock", Clock.fixed(at0400Kst, ZoneId.of("Asia/Seoul")));
            when(domesticRankingClient.fetchRanking())
                    .thenThrow(new RuntimeException("KRX 실패"))
                    .thenReturn(List.of());

            // Act: 1회 예외, 2회 정상
            scheduler.snapshotKrx();
            scheduler.snapshotKrx();

            // Assert: fetchRanking 2회 호출됨(가드 reset 확인)
            verify(domesticRankingClient, times(2)).fetchRanking();
        }

        @Test
        @DisplayName("US 잡 예외 종료 후 가드 reset → 다음 cycle 정상 실행")
        void snapshotUs_exceptionThenReset_nextCycleRuns() {
            when(overseasRankingClient.fetchRanking())
                    .thenThrow(new RuntimeException("US 실패"))
                    .thenReturn(List.of());

            scheduler.snapshotUs();
            scheduler.snapshotUs();

            verify(overseasRankingClient, times(2)).fetchRanking();
        }

        @Test
        @DisplayName("KRX 가드와 US 가드는 독립 — KRX 진행 중 US 잡은 정상 실행")
        void krxAndUsGuards_areIndependent() {
            // Arrange: KRX 가드만 set
            ReflectionTestUtils.setField(scheduler, "krxRunning", new AtomicBoolean(true));
            when(overseasRankingClient.fetchRanking()).thenReturn(List.of());

            // Act
            scheduler.snapshotUs();

            // Assert: US fetch는 호출됨 (독립 가드)
            verify(overseasRankingClient).fetchRanking();
            verify(domesticRankingClient, never()).fetchRanking();
        }
    }
}
