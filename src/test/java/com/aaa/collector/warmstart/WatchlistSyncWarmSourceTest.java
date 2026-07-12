package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.StockGradeRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName(
        "WatchlistSyncWarmSource — watchlist-sync-krx/us seed 값 해석"
                + " (SPEC-COLLECTOR-EXPECTED-RUN-001 §13 O-3)")
class WatchlistSyncWarmSourceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private StockGradeRepository stockGradeRepository;
    @InjectMocks private WatchlistSyncWarmSource warmSource;

    @Test
    @DisplayName("krxLastLoad는 국내 시장(KOSPI/KOSDAQ/KRX)만 조회한다")
    void krxLastLoadQueriesDomesticMarketsOnly() {
        when(stockGradeRepository.findMaxGradedAtByMarketsIn(any())).thenReturn(Optional.empty());

        warmSource.krxLastLoad();

        ArgumentCaptor<List<Market>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockGradeRepository).findMaxGradedAtByMarketsIn(captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(Market.KOSPI, Market.KOSDAQ, Market.KRX);
    }

    @Test
    @DisplayName("usLastLoad는 해외 시장(NYSE/NASDAQ/AMEX/US)만 조회한다")
    void usLastLoadQueriesOverseasMarketsOnly() {
        when(stockGradeRepository.findMaxGradedAtByMarketsIn(any())).thenReturn(Optional.empty());

        warmSource.usLastLoad();

        ArgumentCaptor<List<Market>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockGradeRepository).findMaxGradedAtByMarketsIn(captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(Market.NYSE, Market.NASDAQ, Market.AMEX, Market.US);
    }

    @Test
    @DisplayName("UTC로 저장된 graded_at(NORMALIZE_UTC)을 KST 벽시계로 정확히 변환한다")
    void convertsUtcGradedAtToKstWallClock() {
        // Arrange: UTC 2026-07-12 00:20:05 == KST 2026-07-12 09:20:05
        ZonedDateTime utcGradedAt = ZonedDateTime.of(2026, 7, 12, 0, 20, 5, 0, ZoneOffset.UTC);
        when(stockGradeRepository.findMaxGradedAtByMarketsIn(any()))
                .thenReturn(Optional.of(utcGradedAt));

        // Act
        Optional<LocalDateTime> result = warmSource.krxLastLoad();

        // Assert — 동일 순간(Instant)을 가리키되 벽시계 표현은 KST
        assertThat(result).contains(LocalDateTime.of(2026, 7, 12, 9, 20, 5));
        assertThat(result.get().atZone(KST).toInstant()).isEqualTo(utcGradedAt.toInstant());
    }

    @Test
    @DisplayName("데이터가 없으면 빈 Optional을 그대로 전달한다 (warm-start skip)")
    void passesThroughEmpty() {
        when(stockGradeRepository.findMaxGradedAtByMarketsIn(any())).thenReturn(Optional.empty());

        assertThat(warmSource.krxLastLoad()).isEmpty();
        assertThat(warmSource.usLastLoad()).isEmpty();
    }
}
