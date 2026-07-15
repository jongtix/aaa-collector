package com.aaa.collector.market.indicator.vix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.config.InserterProperties;
import com.aaa.collector.market.MarketIndicator;
import com.aaa.collector.market.MarketIndicatorInserter;
import com.aaa.collector.market.MarketIndicatorRepository;
import com.aaa.collector.market.enums.IndicatorCode;
import com.aaa.collector.market.indicator.MarketIndicatorRow;
import com.aaa.collector.market.indicator.MarketIndicatorSourceChain;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("VixCollectionService 단위 테스트 (SPEC-COLLECTOR-MARKETIND-003)")
class VixCollectionServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Mock private MarketIndicatorSourceChain vixChain;
    @Mock private MarketIndicatorRepository marketIndicatorRepository;
    @Mock private MarketIndicatorInserter marketIndicatorInserter;

    @Captor private ArgumentCaptor<List<MarketIndicator>> inserterCaptor;

    /** 기본 청크 크기 1000 (REQ-INSERT-010). */
    private final InserterProperties inserterProperties = new InserterProperties();

    private VixCollectionService service;

    @BeforeEach
    void setUp() {
        service =
                new VixCollectionService(
                        vixChain,
                        marketIndicatorRepository,
                        marketIndicatorInserter,
                        inserterProperties);
    }

    private MarketIndicatorRow vixRow(LocalDate date) {
        return vixRow(date, "CBOE");
    }

    private MarketIndicatorRow vixRow(LocalDate date, String source) {
        return new MarketIndicatorRow(
                IndicatorCode.VIX,
                date,
                new BigDecimal("18.0000"),
                new BigDecimal("19.0000"),
                new BigDecimal("17.0000"),
                new BigDecimal("18.5000"),
                source);
    }

    @Nested
    @DisplayName("collectDaily — 윈도우 수집 (REQ-001, REQ-002, REQ-003, REQ-004)")
    class CollectDailyWindow {

        @Test
        @DisplayName("today에서 [today-14, today-1] 윈도우를 파생해 fetchRange 호출")
        void derivesWindow_andCallsFetchRange() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            LocalDate expectedFrom = today.minusDays(VixCollectionService.WINDOW_LOOKBACK_DAYS);
            LocalDate expectedTo = today.minusDays(VixCollectionService.WINDOW_END_OFFSET_DAYS);
            when(vixChain.fetchRange(expectedFrom, expectedTo)).thenReturn(List.of());

            service.collectDaily(today);

            verify(vixChain).fetchRange(expectedFrom, expectedTo);
        }

        @Test
        @DisplayName("윈도우 상한은 항상 today-1 — 당일을 포함하지 않는다 (REQ-002)")
        void windowUpperBound_excludesToday() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of());

            service.collectDaily(today);

            ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(vixChain).fetchRange(any(), toCaptor.capture());
            assertThat(toCaptor.getValue()).isEqualTo(today.minusDays(1));
            assertThat(toCaptor.getValue()).isBefore(today);
        }

        @Test
        @DisplayName("윈도우 lookback·offset은 명명 상수로 보유한다 (REQ-003)")
        void windowConstants_areNamedConstants() {
            assertThat(VixCollectionService.WINDOW_LOOKBACK_DAYS).isEqualTo(14);
            assertThat(VixCollectionService.WINDOW_END_OFFSET_DAYS).isEqualTo(1);
        }

        @Test
        @DisplayName("정상 수집 — insertBatch 1회 호출 (AC-4 배치 통합)")
        void normalCollect_insertsRow() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            LocalDate validDate = today.minusDays(2); // 확정된 과거 거래일
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of(vixRow(validDate)));

            service.collectDaily(today);

            verify(marketIndicatorInserter, times(1)).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted = inserterCaptor.getValue();
            assertThat(inserted).hasSize(1);
            assertThat(inserted.getFirst().getIndicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(inserted.getFirst().getCloseValue()).isEqualByComparingTo("18.5000");
        }

        @Test
        @DisplayName("빈 결과 — insertBatch 미호출 (AC-9)")
        void emptyResult_noInsert() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of());

            service.collectDaily(today);

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("close null 행 skip (REQ-034)")
        void nullCloseRow_skipped() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            LocalDate validDate = today.minusDays(2);
            MarketIndicatorRow badRow =
                    new MarketIndicatorRow(
                            IndicatorCode.VIX, validDate, null, null, null, null, "CBOE");
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of(badRow));

            service.collectDaily(today);

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("체인 예외 — 전파 없음 (REQ-003)")
        void chainException_notPropagated() {
            LocalDate today = LocalDate.of(2026, 7, 15);
            when(vixChain.fetchRange(any(), any())).thenThrow(new RuntimeException("수집 실패"));

            assertThatCode(() -> service.collectDaily(today)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("saveRows 공유 가드 — 비확정 행(당일 이후, NY 기준) 배제 (REQ-006, AC-10)")
    class NonFinalRowGuard {

        @Test
        @DisplayName("혼합 픽스처 — 확정 N행 + 진행 중 부분 일봉 1행 → N행만 저장(부분 바 배제)")
        void mixedFixture_excludesOnlyTodayRow() {
            LocalDate today = LocalDate.now(NEW_YORK);
            LocalDate confirmed1 = today.minusDays(3);
            LocalDate confirmed2 = today.minusDays(2);
            LocalDate partialBarToday = today; // 진행 중 — 비확정
            when(vixChain.fetchRange(any(), any()))
                    .thenReturn(
                            List.of(
                                    vixRow(confirmed1),
                                    vixRow(confirmed2),
                                    vixRow(partialBarToday, "YAHOO")));

            service.collectDaily(today.plusDays(1));

            verify(marketIndicatorInserter, times(1)).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted = inserterCaptor.getValue();
            assertThat(inserted).hasSize(2);
            assertThat(inserted)
                    .extracting(MarketIndicator::getTradeDate)
                    .containsExactlyInAnyOrder(confirmed1, confirmed2);
        }

        @Test
        @DisplayName("과거 확정 행만 존재 — 전량 저장(no-op 케이스, CBOE 정규 시각 전량 저장 상당)")
        void allConfirmedRows_allSaved() {
            LocalDate today = LocalDate.now(NEW_YORK);
            LocalDate d1 = today.minusDays(5);
            LocalDate d2 = today.minusDays(4);
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of(vixRow(d1), vixRow(d2)));

            service.collectDaily(today.plusDays(1));

            verify(marketIndicatorInserter, times(1)).insertBatch(inserterCaptor.capture());
            assertThat(inserterCaptor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("당일 행만 존재 — 저장 0건(전량 배제)")
        void onlyTodayRow_noneSaved() {
            LocalDate today = LocalDate.now(NEW_YORK);
            when(vixChain.fetchRange(any(), any())).thenReturn(List.of(vixRow(today, "YAHOO")));

            service.collectDaily(today.plusDays(1));

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }
    }

    @Nested
    @DisplayName("collectHistory — 전체 이력 수집(공유 saveRows 가드 적용)")
    class CollectHistory {

        @Test
        @DisplayName("다수 행 삽입")
        void multipleRows_allInserted() {
            List<MarketIndicatorRow> rows =
                    List.of(vixRow(LocalDate.of(2026, 1, 2)), vixRow(LocalDate.of(2026, 1, 5)));
            when(vixChain.fetchHistory()).thenReturn(rows);

            int count = service.collectHistory();

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("당일(NY 기준) 이후 행은 배제된다 (REQ-006 — 히스토리 경로 공통 적용)")
        void excludesTodayRowFromHistory() {
            LocalDate today = LocalDate.now(NEW_YORK);
            List<MarketIndicatorRow> rows =
                    List.of(vixRow(today.minusDays(1)), vixRow(today, "YAHOO"));
            when(vixChain.fetchHistory()).thenReturn(rows);

            int count = service.collectHistory();

            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("청크 분할 — 대용량 배치 (AC-5, REQ-INSERT-010)")
    class ChunkSplit {

        @Test
        @DisplayName("AC-5 — 9200행 + chunkSize=1000 → insertBatch 10회 호출")
        void largeHistory_chunkedToExact10Batches() {
            // Arrange — 9200행 = 9 × 1000 + 200, 전부 과거 확정일
            List<MarketIndicatorRow> rows = new ArrayList<>();
            LocalDate base = LocalDate.of(1990, 1, 2);
            for (int i = 0; i < 9200; i++) {
                rows.add(vixRow(base.plusDays(i)));
            }
            when(vixChain.fetchHistory()).thenReturn(rows);

            // Act — 기본 chunkSize=1000 사용
            int count = service.collectHistory();

            // Assert — 9200 / 1000 = 9 full chunks + 1 remainder → 10회 호출
            assertThat(count).isEqualTo(9200);
            verify(marketIndicatorInserter, times(10)).insertBatch(any());
        }

        @Test
        @DisplayName("정확히 chunkSize와 같은 행 수 → 1회 insertBatch 호출")
        void exactlyChunkSize_oneBatch() {
            List<MarketIndicatorRow> rows = new ArrayList<>();
            LocalDate base = LocalDate.of(2020, 1, 2);
            for (int i = 0; i < 1000; i++) {
                rows.add(vixRow(base.plusDays(i)));
            }
            when(vixChain.fetchHistory()).thenReturn(rows);

            service.collectHistory();

            verify(marketIndicatorInserter, times(1)).insertBatch(any());
        }
    }
}
