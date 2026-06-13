package com.aaa.collector.watchlist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.StockListService;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetadataWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * WatchlistWriter + refreshCache/markRemoved 동작 회귀 테스트 (시나리오 6).
 *
 * <p>SPEC-COLLECTOR-GRADE-002: classify()는 WatchlistWriter에서 제거됨 — 이 파일은 refreshCache 유지 회귀만 검증.
 * classify 트리거 테스트는 WatchlistSyncServiceTest로 이전되었다(시나리오 1/2/3).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistWriter — refreshCache 회귀 (시나리오 6, SPEC-COLLECTOR-GRADE-002)")
class WatchlistWriterGradeTest {

    @Mock private StockRepository stockRepository;
    @Mock private StockListService stockListService;
    @Mock private EtfMetadataWriter etfMetadataWriter;

    private WatchlistWriter watchlistWriter;

    @BeforeEach
    void setUp() {
        WatchlistEntryWriter entryWriter =
                new WatchlistEntryWriter(stockRepository, etfMetadataWriter);
        watchlistWriter = new WatchlistWriter(stockRepository, entryWriter, stockListService);
        lenient().doNothing().when(stockListService).refreshCache();
    }

    private static ResolvedStock resolvedKospi(String symbol) {
        return new ResolvedStock(symbol, "테스트종목", Market.KOSPI, null);
    }

    @Nested
    @DisplayName("시나리오 6 — failedGroupCount==0 시 refreshCache 회귀")
    class RefreshCacheRegression {

        @Test
        @DisplayName("failedGroupCount=0 — refreshCache() 정확히 1회 호출")
        void upsertAll_noGroupFailed_callsRefreshCacheOnce() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 0);

            verify(stockListService).refreshCache();
        }

        @Test
        @DisplayName("failedGroupCount=1 — refreshCache() 미호출")
        void upsertAll_oneGroupFailed_doesNotCallRefreshCache() {
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            watchlistWriter.upsertAll(List.of(resolvedKospi("005930")), 1);

            verify(stockListService, never()).refreshCache();
        }
    }
}
