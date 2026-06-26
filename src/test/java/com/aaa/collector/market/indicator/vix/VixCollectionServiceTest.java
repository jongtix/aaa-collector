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
@DisplayName("VixCollectionService 단위 테스트")
class VixCollectionServiceTest {

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
        return new MarketIndicatorRow(
                IndicatorCode.VIX,
                date,
                new BigDecimal("18.0000"),
                new BigDecimal("19.0000"),
                new BigDecimal("17.0000"),
                new BigDecimal("18.5000"),
                "CBOE");
    }

    @Nested
    @DisplayName("collectDaily — 일봉 수집")
    class CollectDaily {

        @Test
        @DisplayName("정상 수집 — insertBatch 1회 호출 (AC-4 배치 통합)")
        void normalCollect_insertsRow() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenReturn(List.of(vixRow(date)));

            service.collectDaily(date);

            verify(marketIndicatorInserter, times(1)).insertBatch(inserterCaptor.capture());
            List<MarketIndicator> inserted = inserterCaptor.getValue();
            assertThat(inserted).hasSize(1);
            assertThat(inserted.getFirst().getIndicatorCode()).isEqualTo(IndicatorCode.VIX);
            assertThat(inserted.getFirst().getCloseValue()).isEqualByComparingTo("18.5000");
        }

        @Test
        @DisplayName("빈 결과 — insertBatch 미호출 (AC-9)")
        void emptyResult_noInsert() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenReturn(List.of());

            service.collectDaily(date);

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("close null 행 skip (REQ-034)")
        void nullCloseRow_skipped() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            MarketIndicatorRow badRow =
                    new MarketIndicatorRow(IndicatorCode.VIX, date, null, null, null, null, "CBOE");
            when(vixChain.fetchDaily(date)).thenReturn(List.of(badRow));

            service.collectDaily(date);

            verify(marketIndicatorInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("체인 예외 — 전파 없음 (REQ-003)")
        void chainException_notPropagated() {
            LocalDate date = LocalDate.of(2026, 6, 20);
            when(vixChain.fetchDaily(date)).thenThrow(new RuntimeException("수집 실패"));

            assertThatCode(() -> service.collectDaily(date)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("collectHistory — 전체 이력 수집")
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
    }

    @Nested
    @DisplayName("청크 분할 — 대용량 배치 (AC-5, REQ-INSERT-010)")
    class ChunkSplit {

        @Test
        @DisplayName("AC-5 — 9200행 + chunkSize=1000 → insertBatch 10회 호출")
        void largeHistory_chunkedToExact10Batches() {
            // Arrange — 9200행 = 9 × 1000 + 200
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
