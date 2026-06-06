package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WatchlistWriterTest {

    @Mock private StockRepository stockRepository;
    private WatchlistWriter watchlistWriter;

    @BeforeEach
    void setUp() {
        WatchlistEntryWriter entryWriter = new WatchlistEntryWriter(stockRepository);
        watchlistWriter = new WatchlistWriter(stockRepository, entryWriter);
    }

    private static Stock stockWith(
            String symbol, Market market, String nameKo, String nameEn, boolean active) {
        return stockWith(symbol, market, nameKo, nameEn, active, null, 1L);
    }

    private static Stock stockWith(
            String symbol,
            Market market,
            String nameKo,
            String nameEn,
            boolean active,
            LocalDateTime watchlistRemovedAt) {
        return stockWith(symbol, market, nameKo, nameEn, active, watchlistRemovedAt, 1L);
    }

    private static Stock stockWith(
            String symbol,
            Market market,
            String nameKo,
            String nameEn,
            boolean active,
            LocalDateTime watchlistRemovedAt,
            long id) {
        Stock s =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo(nameKo)
                        .nameEn(nameEn)
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .active(active)
                        .watchlistRemovedAt(watchlistRemovedAt)
                        .build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Nested
    @DisplayName("upsertAll — 빈 입력")
    class EmptyInput {

        @Test
        @DisplayName("빈 목록 — findAllBySymbolIn 미호출, save 미호출")
        void upsertAll_emptyList_noRepositoryCalls() {
            watchlistWriter.upsertAll(List.of(), 0);

            verify(stockRepository, never()).findAllBySymbolIn(anyCollection());
            verify(stockRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("upsertAll — 그룹 부분 실패")
    class PartialGroupFailure {

        @Test
        @DisplayName("failedGroupCount=1 — markWatchlistRemoved 미호출")
        void upsertAll_oneGroupFailed_skipsMarkWatchlistRemoved() {
            ResolvedStock resolved = new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null);
            Stock removedStock = stockWith("005930", Market.KOSPI, "삼성전자", null, true, null, 2L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(removedStock));

            watchlistWriter.upsertAll(List.of(resolved), 1);

            verify(stockRepository, never()).markWatchlistRemoved(any());
        }

        @Test
        @DisplayName("failedGroupCount=0 — markWatchlistRemoved 정상 호출")
        void upsertAll_noGroupFailed_callsMarkWatchlistRemoved() {
            ResolvedStock resolved = new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null);
            Stock removedStock = stockWith("005930", Market.KOSPI, "삼성전자", null, true, null, 2L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(removedStock));

            watchlistWriter.upsertAll(List.of(resolved), 0);

            verify(stockRepository).markWatchlistRemoved(Set.of(2L));
        }

        @Test
        @DisplayName("failedGroupCount=3 (전체 실패) — markWatchlistRemoved 미호출")
        void upsertAll_allGroupsFailed_skipsMarkWatchlistRemoved() {
            ResolvedStock resolved = new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null);
            Stock removedStock = stockWith("005930", Market.KOSPI, "삼성전자", null, true, null, 2L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(removedStock));

            watchlistWriter.upsertAll(List.of(resolved), 3);

            verify(stockRepository, never()).markWatchlistRemoved(any());
        }
    }

    @Nested
    @DisplayName("upsertAll — 종목 처리")
    class StockProcessing {

        @Test
        @DisplayName("신규 종목 + StockInfo 있음 — assetType, nameEn, listedDate 채워서 save")
        void upsertAll_newStockWithStockInfo_savesWithFullFields() {
            // Arrange
            StockInfo info = new StockInfo(AssetType.ETF, "Samsung ETF", LocalDate.of(2020, 1, 15));
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, info);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(captor.capture());
            Stock saved = captor.getValue();
            assertThat(saved.getSymbol()).isEqualTo("005930");
            assertThat(saved.getNameKo()).isEqualTo("삼성전자");
            assertThat(saved.getNameEn()).isEqualTo("Samsung ETF");
            assertThat(saved.getMarket()).isEqualTo(Market.KOSPI);
            assertThat(saved.getAssetType()).isEqualTo(AssetType.ETF);
            assertThat(saved.getListedDate()).isEqualTo(LocalDate.of(2020, 1, 15));
            assertThat(saved.isActive()).isTrue();
        }

        @Test
        @DisplayName("신규 종목 + StockInfo 없음 — assetType=STOCK, nameEn/listedDate=null로 save")
        void upsertAll_newStockWithoutStockInfo_savesWithDefaults() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(captor.capture());
            Stock saved = captor.getValue();
            assertThat(saved.getAssetType()).isEqualTo(AssetType.STOCK);
            assertThat(saved.getNameEn()).isNull();
            assertThat(saved.getListedDate()).isNull();
        }

        @Test
        @DisplayName("nameKo 변경된 기존 종목 — 엔티티 nameKo 필드가 갱신됨")
        void upsertAll_nameKoChanged_updatesNameKoOnEntity() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자 (신)", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getNameKo()).isEqualTo("삼성전자 (신)");
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("nameEn 변경된 기존 종목 — 엔티티 nameEn 필드가 갱신됨")
        void upsertAll_nameEnChanged_updatesNameEnOnEntity() {
            // Arrange
            StockInfo info = new StockInfo(AssetType.STOCK, "Samsung Electronics New", null);
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, info);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", "Samsung Electronics", true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getNameEn()).isEqualTo("Samsung Electronics New");
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("비활성 기존 종목 (이름 동일) — 엔티티 active 필드가 true로 갱신됨")
        void upsertAll_inactiveStock_activatesEntity() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, false);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.isActive()).isTrue();
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("nameKo 변경 + 비활성 종목 — nameKo와 active 모두 갱신됨")
        void upsertAll_nameKoChangedAndInactive_updatesBothFields() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자 (신)", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, false);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getNameKo()).isEqualTo("삼성전자 (신)");
            assertThat(existing.isActive()).isTrue();
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("이름·활성 상태 변경 없음 — 엔티티 필드 변경 없음, save 미호출")
        void upsertAll_noChange_noRepositoryCalls() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("관심목록에서 제거된 기존 종목 — markWatchlistRemoved() 호출")
        void upsertAll_stockRemovedFromWatchlist_callsMarkWatchlistRemoved() {
            // Arrange — DB에 005930 있지만 이번 sync에는 000660만 존재
            ResolvedStock resolved = new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null);
            Stock removedStock = stockWith("005930", Market.KOSPI, "삼성전자", null, true, null, 2L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(removedStock));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            verify(stockRepository).markWatchlistRemoved(Set.of(2L));
        }

        @Test
        @DisplayName("관심목록 재추가 종목 (watchlistRemovedAt != null) — 엔티티 watchlistRemovedAt이 null로 갱신됨")
        void upsertAll_stockReaddedToWatchlist_resetsWatchlistRemovedAtOnEntity() {
            // Arrange
            Stock existing =
                    stockWith(
                            "005930",
                            Market.KOSPI,
                            "삼성전자",
                            null,
                            true,
                            LocalDateTime.of(2026, 1, 1, 0, 0));
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 0);

            // Assert
            assertThat(existing.getWatchlistRemovedAt()).isNull();
            verify(stockRepository, never()).markWatchlistRemoved(any());
        }

        @Test
        @DisplayName("이번 sync에 있는 종목 — markWatchlistRemoved() 미호출")
        void upsertAll_stockPresentInSync_doesNotCallMarkWatchlistRemoved() {
            // Arrange
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 0);

            // Assert
            verify(stockRepository, never()).markWatchlistRemoved(any());
        }

        @Test
        @DisplayName(
                "동일 symbol 다른 market 기존 종목 — 신규 market은 insert, 기존 market은 markWatchlistRemoved")
        void upsertAll_sameSymbolDifferentMarket_existingMarketRemoved() {
            // Arrange — DB에 ACME:NYSE 있지만 이번 sync에는 ACME:NASDAQ만 존재
            ResolvedStock resolved = new ResolvedStock("ACME", "ACME Inc", Market.NASDAQ, null);
            Stock existingNyse = stockWith("ACME", Market.NYSE, "ACME Inc", null, true, null, 10L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existingNyse));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            verify(stockRepository).markWatchlistRemoved(Set.of(10L));
        }

        @Test
        @DisplayName("StockInfo.nameEn null — 기존 nameEn 보존")
        void upsertAll_stockInfoNameEnNull_preservesExistingNameEn() {
            // Arrange — StockInfo는 있지만 nameEn이 null
            StockInfo infoWithoutNameEn = new StockInfo(AssetType.STOCK, null, null);
            ResolvedStock resolved =
                    new ResolvedStock("005930", "삼성전자", Market.KOSPI, infoWithoutNameEn);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", "Samsung Electronics", true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getNameEn()).isEqualTo("Samsung Electronics");
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("벌크 조회 — 모든 symbol을 한 번의 findAllBySymbolIn 호출로 조회")
        void upsertAll_multipleStocks_singleBulkSelect() {
            // Arrange
            ResolvedStock s1 = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            ResolvedStock s2 = new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());

            // Act
            watchlistWriter.upsertAll(List.of(s1, s2), 0);

            // Assert
            verify(stockRepository).findAllBySymbolIn(any());
        }
    }
}
