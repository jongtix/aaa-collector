package com.aaa.collector.dart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.dart.disclosure.DartDisclosurePollingService;
import com.aaa.collector.dart.disclosure.DisclosureInserter;
import com.aaa.collector.dart.disclosure.DisclosureRepository;
import com.aaa.collector.dart.disclosure.DisclosureRow;
import com.aaa.collector.dart.external.DartDisclosureClient;
import com.aaa.collector.dart.external.DartListResponse;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** DART 공시 폴링 서비스 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-003, 010, 011, 013). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DartDisclosurePollingServiceTest")
class DartDisclosurePollingServiceTest {

    @Mock private DartDisclosureClient dartDisclosureClient;

    @Mock private DisclosureRepository disclosureRepository;

    @Mock private DisclosureInserter disclosureInserter;

    @Mock private StockRepository stockRepository;

    @Mock private BatchMetrics batchMetrics;

    @Captor private ArgumentCaptor<List<DisclosureRow>> inserterCaptor;

    @InjectMocks private DartDisclosurePollingService pollingService;

    private Stock mockStock(Long id, String symbol) {
        Stock stock = org.mockito.Mockito.mock(Stock.class);
        when(stock.getId()).thenReturn(id);
        when(stock.getSymbol()).thenReturn(symbol);
        return stock;
    }

    private DartListResponse.DisclosureItem makeItem(
            String stockCode, String rceptNo, String rceptDt) {
        return DartListResponse.DisclosureItem.of(
                "00000001", "Y", stockCode, "사업보고서", rceptNo, "삼성전자", rceptDt, null);
    }

    @Nested
    @DisplayName("stock_code 매칭 — watchlist 필터")
    class StockCodeMatching {

        @Test
        @DisplayName("watchlist 매칭 종목 → insertIgnore 호출, 파라미터 검증")
        void watchlistMatch_callsInsertIgnoreWithCorrectRow() {
            // Arrange
            Stock stock = mockStock(10L, "005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            DartListResponse.DisclosureItem item = makeItem("005930", "20260601000001", "20260601");
            when(dartDisclosureClient.fetchAllPages(any(), any())).thenReturn(List.of(item));

            // Act
            pollingService.poll();

            // Assert — DisclosureRow 파라미터 캡처 후 필드 검증
            verify(disclosureInserter).insertBatch(inserterCaptor.capture());
            DisclosureRow captured =
                    inserterCaptor.getAllValues().stream()
                            .flatMap(List::stream)
                            .findFirst()
                            .orElseThrow();
            assertThat(captured.stockId()).isEqualTo(10L);
            assertThat(captured.stockCode()).isEqualTo("005930");
            assertThat(captured.rceptNo()).isEqualTo("20260601000001");
            assertThat(captured.rceptDt()).isEqualTo(LocalDate.of(2026, 6, 1));
        }

        @Test
        @DisplayName("빈 stock_code 항목 → insertIgnore 미호출 (비상장사 스킵)")
        void emptyStockCode_skipsInsert() {
            // Arrange
            Stock stock = mockStock(10L, "005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            DartListResponse.DisclosureItem item = makeItem("", "20260601000002", "20260601");
            when(dartDisclosureClient.fetchAllPages(any(), any())).thenReturn(List.of(item));

            // Act
            pollingService.poll();

            // Assert
            verify(disclosureInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("비watchlist stock_code → insertIgnore 미호출")
        void nonWatchlistStockCode_skipsInsert() {
            // Arrange
            Stock stock = mockStock(10L, "005930");
            when(stockRepository.findAllActive()).thenReturn(List.of(stock));
            DartListResponse.DisclosureItem item = makeItem("000660", "20260601000003", "20260601");
            when(dartDisclosureClient.fetchAllPages(any(), any())).thenReturn(List.of(item));

            // Act
            pollingService.poll();

            // Assert
            verify(disclosureInserter, never()).insertBatch(any());
        }

        @Test
        @DisplayName("활성 종목 없을 때 → fetchAllPages 미호출")
        void noActiveStocks_skipsFetch() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            pollingService.poll();

            verify(dartDisclosureClient, never()).fetchAllPages(any(), any());
        }
    }

    @Nested
    @DisplayName("BatchMetrics 계측")
    class Metrics {

        @Test
        @DisplayName("폴링 완료 시 recordCompletion 호출")
        void poll_callsRecordCompletion() {
            when(stockRepository.findAllActive()).thenReturn(List.of());

            pollingService.poll();

            verify(batchMetrics)
                    .recordCompletion(
                            eq("dart-disclosure"), anyLong(), anyLong(), anyLong(), anyLong());
        }
    }
}
