package com.aaa.collector.watchlist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListService;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.GradeClassificationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * WatchlistWriter + GradeClassificationService 트리거 연결 테스트 (시나리오 1).
 *
 * <p>WatchlistWriter의 기존 테스트(WatchlistWriterTest)와 중복 없이 등급 분류 트리거 동작만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistWriter — GradeClassificationService 트리거 (시나리오 1)")
class WatchlistWriterGradeTest {

    @Mock private StockRepository stockRepository;
    @Mock private StockListService stockListService;
    @Mock private GradeClassificationService gradeClassificationService;

    private WatchlistWriter watchlistWriter;

    @BeforeEach
    void setUp() {
        WatchlistEntryWriter entryWriter = new WatchlistEntryWriter(stockRepository);
        watchlistWriter =
                new WatchlistWriter(
                        stockRepository, entryWriter, stockListService, gradeClassificationService);
        lenient().doNothing().when(stockListService).refreshCache();
        lenient().doNothing().when(gradeClassificationService).classify();
    }

    private static ResolvedStock resolvedKospi(String symbol) {
        return new ResolvedStock(symbol, "테스트종목", Market.KOSPI, null);
    }

    private static Stock existingStock(String symbol, long id) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Nested
    @DisplayName("시나리오 1 — sync 성공 후 classify() 트리거")
    class GradeTrigger {

        @Test
        @DisplayName("failedGroupCount=0 — refreshCache() 후 classify() 정확히 1회 호출")
        void upsertAll_noGroupFailed_callsClassifyOnce() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 0);

            verify(stockListService).refreshCache();
            verify(gradeClassificationService).classify();
        }

        @Test
        @DisplayName("failedGroupCount=1 — classify() 미호출")
        void upsertAll_oneGroupFailed_doesNotCallClassify() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 1);

            verify(gradeClassificationService, never()).classify();
        }

        @Test
        @DisplayName("failedGroupCount=3 — classify() 미호출")
        void upsertAll_allGroupsFailed_doesNotCallClassify() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 3);

            verify(gradeClassificationService, never()).classify();
        }

        @Test
        @DisplayName("classify() 예외 발생 — sync 결과에 영향 없음 (try-catch 격리)")
        void upsertAll_classifyThrows_doesNotAffectSyncResult() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());
            org.mockito.Mockito.doThrow(new RuntimeException("등급 분류 실패"))
                    .when(gradeClassificationService)
                    .classify();

            // Act & Assert — 예외 전파 없음
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 0));
        }
    }
}
