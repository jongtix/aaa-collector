package com.aaa.collector.dart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.backfill.BackfillProperties;
import com.aaa.collector.backfill.BackfillStatus;
import com.aaa.collector.backfill.BackfillStatusRepository;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillOrchestrator;
import com.aaa.collector.dart.backfill.DartDisclosureBackfillWindowService;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** DART 공시 백필 오케스트레이터 단위 테스트 (SPEC-COLLECTOR-DART-001 REQ-DART-020~023). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DartDisclosureBackfillOrchestratorTest")
class DartDisclosureBackfillOrchestratorTest {

    @Mock private BackfillStatusRepository backfillStatusRepository;
    @Mock private DartDisclosureBackfillWindowService windowService;
    @Mock private StockRepository stockRepository;
    @Mock private BackfillProperties backfillProperties;

    @InjectMocks private DartDisclosureBackfillOrchestrator orchestrator;

    private Stock mockStock(Long id, String symbol) {
        Stock stock = Mockito.mock(Stock.class);
        when(stock.getId()).thenReturn(id);
        when(stock.getSymbol()).thenReturn(symbol);
        return stock;
    }

    private BackfillStatus mockStatus(Long id, String symbol) {
        BackfillStatus status = Mockito.mock(BackfillStatus.class);
        when(status.getId()).thenReturn(id);
        when(status.getTargetCode()).thenReturn(symbol);
        return status;
    }

    @Nested
    @DisplayName("per-table-completion-cap 적용")
    class PerTableCap {

        @Test
        @DisplayName("cap=2이고 PENDING 3건 → executeWindow 2회만 호출")
        void capLimitsWindowExecutions() {
            // Arrange
            when(backfillProperties.getPerTableCompletionCap()).thenReturn(2);
            Stock s1 = mockStock(1L, "SYM1");
            Stock s2 = mockStock(2L, "SYM2");
            Stock s3 = mockStock(3L, "SYM3");
            when(stockRepository.findAllActive()).thenReturn(List.of(s1, s2, s3));
            BackfillStatus st1 = mockStatus(1L, "SYM1");
            BackfillStatus st2 = mockStatus(2L, "SYM2");
            BackfillStatus st3 = mockStatus(3L, "SYM3");
            when(backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                            any(), any(), any()))
                    .thenReturn(List.of(st1, st2, st3));

            // Act
            orchestrator.run();

            // Assert
            verify(windowService, times(2)).executeWindow(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("data_table=disclosures 필터 사용")
    class DataTableFilter {

        @Test
        @DisplayName("findByStatusIn...OrderById 호출 시 data_table=\"disclosures\" 인자 전달")
        void queryUsesDisclosuresDataTable() {
            // Arrange
            when(backfillProperties.getPerTableCompletionCap()).thenReturn(10);
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                            any(), any(), any()))
                    .thenReturn(List.of());

            // Act
            orchestrator.run();

            // Assert
            verify(backfillStatusRepository)
                    .findByStatusInAndTargetTypeAndDataTableOrderById(
                            any(), eq("STOCK"), eq("disclosures"));
        }
    }

    @Nested
    @DisplayName("처리 대상 없음")
    class NoPendingItems {

        @Test
        @DisplayName("빈 목록 반환 → executeWindow 미호출")
        void emptyPendingList_windowServiceNotCalled() {
            when(backfillProperties.getPerTableCompletionCap()).thenReturn(10);
            when(stockRepository.findAllActive()).thenReturn(List.of());
            when(backfillStatusRepository.findByStatusInAndTargetTypeAndDataTableOrderById(
                            any(), any(), any()))
                    .thenReturn(List.of());

            orchestrator.run();

            verify(windowService, never()).executeWindow(any(), any(), any());
        }
    }
}
