package com.aaa.collector.stock.grade.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.grade.AdtvPercentileCalculator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RankingSnapshotService 단위 테스트")
class RankingSnapshotServiceTest {

    @Mock private RankingSnapshotRepository snapshotRepository;
    @InjectMocks private RankingSnapshotService service;

    // 2024-06-17 04:00 KST = 2024-06-16 19:00:00 UTC
    // KST 날짜 = 2024-06-17
    private static final Instant KRX_CAPTURE_INSTANT =
            Instant.parse("2024-06-16T19:00:00Z"); // 04:00 KST
    // 2024-06-17 16:00 KST = 2024-06-17 07:00:00 UTC
    // ET-EDT 날짜(UTC-4): 07:00 UTC = 03:00 EDT → 날짜 = 2024-06-17
    // ET-EST 날짜(UTC-5): 07:00 UTC = 02:00 EST → 날짜 = 2024-06-17
    private static final Instant US_CAPTURE_INSTANT =
            Instant.parse("2024-06-17T07:00:00Z"); // 16:00 KST

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // -----------------------------------------------------------------------
    // 시나리오 1-3: snapshot_date 도출 (C1)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1-3 — snapshot_date 도출 (C1): KRX=KST 날짜, US=ET 날짜")
    class SnapshotDateDerivation {

        @Test
        @DisplayName("KRX 04:00 KST captured_at → snapshot_date = KST 날짜")
        void deriveSnapshotDate_krx_returnsKstDate() {
            // 2024-06-17 04:00 KST → KST 날짜 = 2024-06-17
            LocalDate result = service.deriveSnapshotDate(KRX_CAPTURE_INSTANT, "KRX");
            assertThat(result).isEqualTo(LocalDate.of(2024, 6, 17));
        }

        @Test
        @DisplayName("US 16:00 KST captured_at (EDT) → snapshot_date = ET 날짜 (자정 롤오버 없음)")
        void deriveSnapshotDate_usEdt_returnsEtDate() {
            // 2024-06-17 07:00 UTC = 2024-06-17 03:00 EDT → ET 날짜 = 2024-06-17
            LocalDate result = service.deriveSnapshotDate(US_CAPTURE_INSTANT, "US");
            assertThat(result).isEqualTo(LocalDate.of(2024, 6, 17));
        }

        @Test
        @DisplayName("US 16:00 KST captured_at (EST) → snapshot_date = ET 날짜 (자정 롤오버 없음)")
        void deriveSnapshotDate_usEst_returnsEtDate() {
            // 2024-12-17 16:00 KST = 2024-12-17 07:00 UTC = 2024-12-17 02:00 EST → ET 날짜 =
            // 2024-12-17
            Instant estInstant = Instant.parse("2024-12-17T07:00:00Z");
            LocalDate result = service.deriveSnapshotDate(estInstant, "US");
            assertThat(result).isEqualTo(LocalDate.of(2024, 12, 17));
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 2-3: degenerate 임계 (M1)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 2-3 — degenerate 임계 (M1)")
    class DegenerateThreshold {

        @Test
        @DisplayName("직전 N=100건 시 count=49 → degenerate (SKIP)")
        void isDegenerate_prevExists_countBelowHalf_true() {
            assertThat(service.isDegenerate(49, 100)).isTrue();
        }

        @Test
        @DisplayName("직전 N=100건 시 count=50 → 정상 (저장)")
        void isDegenerate_prevExists_countAtHalf_false() {
            assertThat(service.isDegenerate(50, 100)).isFalse();
        }

        @Test
        @DisplayName("cold: prevCount=0, count=19 → degenerate (SKIP)")
        void isDegenerate_cold_countBelowMin_true() {
            assertThat(service.isDegenerate(19, 0)).isTrue();
        }

        @Test
        @DisplayName("cold: prevCount=0, count=20 → 정상 (저장)")
        void isDegenerate_cold_countAtMin_false() {
            assertThat(service.isDegenerate(20, 0)).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 1-1 / 1-2: save (원자 저장)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1-1/1-2 — 원자 저장 (percentile 미저장, INSERT IGNORE)")
    class SaveSnapshot {

        @Test
        @DisplayName("정상 엔트리 리스트 저장 시 insertIgnore 엔트리 수만큼 호출, percentile 컬럼 없음")
        void saveSnapshot_normalEntries_savesAllWithNoPct() {
            // Arrange
            Clock clock = Clock.fixed(KRX_CAPTURE_INSTANT, KST);
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry("005930", 100.0),
                            new AdtvPercentileCalculator.RankEntry("000660", 50.0));
            // prevCount: 직전 스냅샷 날짜 존재, count=2 → degenerate(2, 2) = 2 < 1 = false → 저장 진행
            when(snapshotRepository.findLatestSnapshotDate("KRX"))
                    .thenReturn(Optional.of(LocalDate.of(2024, 6, 16)));
            when(snapshotRepository.countByMarketAndSnapshotDate("KRX", LocalDate.of(2024, 6, 16)))
                    .thenReturn(2L);

            // Act
            service.saveSnapshot("KRX", entries, clock);

            // Assert: 엔트리 2건 각각 insertIgnore 호출
            LocalDate expectedDate = LocalDate.of(2024, 6, 17);
            verify(snapshotRepository, times(2))
                    .insertIgnore(
                            eq("KRX"),
                            eq(expectedDate),
                            any(String.class),
                            any(Double.class),
                            any(Integer.class),
                            eq(KRX_CAPTURE_INSTANT));
            verify(snapshotRepository)
                    .insertIgnore(
                            eq("KRX"),
                            eq(expectedDate),
                            eq("005930"),
                            eq(100.0),
                            eq(1),
                            eq(KRX_CAPTURE_INSTANT));
            verify(snapshotRepository)
                    .insertIgnore(
                            eq("KRX"),
                            eq(expectedDate),
                            eq("000660"),
                            eq(50.0),
                            eq(2),
                            eq(KRX_CAPTURE_INSTANT));
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 2-1: fetch 공백 → SKIP
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 2-1 — fetch 공백 → 저장 SKIP")
    class WithholdOnEmpty {

        @Test
        @DisplayName("빈 엔트리 리스트 → insertIgnore 미호출")
        void saveSnapshot_emptyEntries_noSave() {
            Clock clock = Clock.fixed(KRX_CAPTURE_INSTANT, KST);
            service.saveSnapshot("KRX", Collections.emptyList(), clock);
            verify(snapshotRepository, never())
                    .insertIgnore(
                            any(), any(), any(), any(Double.class), any(Integer.class), any());
        }
    }

    // -----------------------------------------------------------------------
    // 시나리오 1-4: 멱등 재실행 — INSERT IGNORE
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("시나리오 1-4 — 멱등 재실행 (INSERT IGNORE idempotency)")
    class IdempotentRerun {

        @Test
        @DisplayName("동일 cycle 재실행 시 insertIgnore 호출 — DELETE 없음")
        void saveSnapshot_idempotentViaInsertIgnore_noDelete() {
            // Arrange
            Clock clock = Clock.fixed(KRX_CAPTURE_INSTANT, KST);
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(new AdtvPercentileCalculator.RankEntry("005930", 100.0));
            // prevCount: 직전 스냅샷 존재 → isDegenerate(1, 1) = 1 < 0.5 = false → 저장 진행
            when(snapshotRepository.findLatestSnapshotDate("KRX"))
                    .thenReturn(Optional.of(LocalDate.of(2024, 6, 15)));
            when(snapshotRepository.countByMarketAndSnapshotDate("KRX", LocalDate.of(2024, 6, 15)))
                    .thenReturn(1L);

            // Act
            service.saveSnapshot("KRX", entries, clock);

            // Assert: insertIgnore 호출, DELETE 없음 (ADR-026 Tier-1)
            verify(snapshotRepository)
                    .insertIgnore(
                            eq("KRX"),
                            eq(LocalDate.of(2024, 6, 17)),
                            eq("005930"),
                            eq(100.0),
                            eq(1),
                            eq(KRX_CAPTURE_INSTANT));
        }
    }

    // -----------------------------------------------------------------------
    // 최신 스냅샷 조회
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("최신 스냅샷 조회")
    class FindLatestSnapshot {

        @Test
        @DisplayName("시장+날짜 최신 스냅샷 목록 조회")
        void findLatestSnapshot_returnsEntriesForDate() {
            // Arrange
            LocalDate date = LocalDate.of(2024, 6, 17);
            RankingSnapshot snap =
                    RankingSnapshot.of("KRX", date, "005930", 100.0, 1, KRX_CAPTURE_INSTANT);
            when(snapshotRepository.findByMarketAndSnapshotDate("KRX", date))
                    .thenReturn(List.of(snap));

            // Act
            List<RankingSnapshot> result = service.findByMarketAndDate("KRX", date);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getSymbol()).isEqualTo("005930");
        }

        @Test
        @DisplayName("스냅샷 없으면 빈 리스트 반환")
        void findLatestSnapshot_noData_emptyList() {
            LocalDate date = LocalDate.of(2024, 6, 17);
            when(snapshotRepository.findByMarketAndSnapshotDate("KRX", date))
                    .thenReturn(Collections.emptyList());

            List<RankingSnapshot> result = service.findByMarketAndDate("KRX", date);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("최신 snapshot_date 조회")
        void findLatestSnapshotDate_returnsMaxDate() {
            when(snapshotRepository.findLatestSnapshotDate("KRX"))
                    .thenReturn(Optional.of(LocalDate.of(2024, 6, 17)));

            Optional<LocalDate> result = service.findLatestSnapshotDate("KRX");
            assertThat(result).contains(LocalDate.of(2024, 6, 17));
        }
    }

    // -----------------------------------------------------------------------
    // prevCount 조회
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("직전 스냅샷 종목 수 조회")
    class PrevCount {

        @Test
        @DisplayName("latestSnapshotDate가 있으면 해당 날짜 count 반환")
        void findPrevSnapshotCount_latestExists_returnsCount() {
            LocalDate latest = LocalDate.of(2024, 6, 16);
            when(snapshotRepository.findLatestSnapshotDate("KRX")).thenReturn(Optional.of(latest));
            when(snapshotRepository.countByMarketAndSnapshotDate("KRX", latest)).thenReturn(120L);

            int count = service.findPrevSnapshotCount("KRX");
            assertThat(count).isEqualTo(120);
        }

        @Test
        @DisplayName("latestSnapshotDate 없으면(cold) 0 반환")
        void findPrevSnapshotCount_noLatest_returnsZero() {
            when(snapshotRepository.findLatestSnapshotDate("KRX")).thenReturn(Optional.empty());

            int count = service.findPrevSnapshotCount("KRX");
            assertThat(count).isZero();
        }
    }
}
