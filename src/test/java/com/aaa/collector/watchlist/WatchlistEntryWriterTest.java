package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.ListingStatus;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// @MX:SPEC: SPEC-COLLECTOR-WLSYNC-008
@ExtendWith(MockitoExtension.class)
@DisplayName("WatchlistEntryWriter — 상폐/거래정지 상태 배선 (REQ-WLSYNC-147, REQ-WLSYNC-150)")
class WatchlistEntryWriterTest {

    @Mock private StockRepository stockRepository;
    @Mock private com.aaa.collector.stock.etf.EtfMetadataWriter etfMetadataWriter;

    private WatchlistEntryWriter entryWriter;

    private void setUp() {
        entryWriter = new WatchlistEntryWriter(stockRepository, etfMetadataWriter);
    }

    private static Stock existingStock(boolean active, LocalDate delistedAt) {
        Stock stock =
                Stock.builder()
                        .symbol("010620")
                        .nameKo("HD현대미포")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .active(active)
                        .delistedAt(delistedAt)
                        .build();
        ReflectionTestUtils.setField(stock, "id", 1L);
        return stock;
    }

    @Nested
    @DisplayName("INSERT 경로 (buildStock)")
    class InsertPath {

        @Test
        @DisplayName("상폐 감지된 신규 종목 — active=false + delistedAt set으로 INSERT (REQ-WLSYNC-147)")
        void newStockDelisted_insertedWithActiveFalseAndDelistedAt() {
            setUp();
            StockInfo info =
                    new StockInfo(
                            AssetType.STOCK,
                            "HD Hyundai Mipo",
                            null,
                            null,
                            Market.KOSPI,
                            ListingStatus.DELISTED,
                            LocalDate.of(2025, 12, 15));
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, info);
            when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            entryWriter.upsertOne(resolved, Map.of(), new WatchlistWriter.Counter());

            ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(captor.capture());
            Stock saved = captor.getValue();
            assertThat(saved.isActive()).isFalse();
            assertThat(saved.getDelistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName("StockInfo 없음(조회 실패) 신규 종목 — active=true 기본값으로 INSERT")
        void newStockWithoutStockInfo_insertedWithActiveTrueDefault() {
            setUp();
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            entryWriter.upsertOne(resolved, Map.of(), new WatchlistWriter.Counter());

            ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("정상 판정 신규 종목 — active=true 유지")
        void newStockNormal_insertedActiveTrue() {
            setUp();
            StockInfo info = new StockInfo(AssetType.STOCK, "Samsung", null, Market.KOSPI);
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, info);
            when(stockRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            entryWriter.upsertOne(resolved, Map.of(), new WatchlistWriter.Counter());

            ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("UPDATE 경로 (updateIfNeeded)")
    class UpdatePath {

        @Test
        @DisplayName("기존 활성 종목 + 거래정지 감지 — active=false로 갱신, delistedAt은 NULL 유지")
        void existingActiveStock_haltedDetected_deactivatesWithoutDelistedAt() {
            setUp();
            Stock existing = existingStock(true, null);
            when(stockRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
            StockInfo info =
                    new StockInfo(
                            AssetType.STOCK,
                            null,
                            null,
                            null,
                            Market.KOSPI,
                            ListingStatus.HALTED,
                            null);
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, info);
            Map<String, Stock> existingByKey = Map.of("010620:KOSPI", existing);

            entryWriter.upsertOne(resolved, existingByKey, new WatchlistWriter.Counter());

            assertThat(existing.isActive()).isFalse();
            assertThat(existing.getDelistedAt()).isNull();
        }

        @Test
        @DisplayName("이미 상폐 확정 종목 + 정상 재감지 — active 복구 금지, delistedAt 불변(REQ-WLSYNC-152)")
        void alreadyDelistedStock_normalDetected_neverRecovers() {
            setUp();
            LocalDate originalDelistedAt = LocalDate.of(2025, 12, 15);
            Stock existing = existingStock(false, originalDelistedAt);
            when(stockRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
            StockInfo info =
                    new StockInfo(
                            AssetType.STOCK,
                            null,
                            null,
                            null,
                            Market.KOSPI,
                            ListingStatus.NORMAL,
                            null);
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, info);
            Map<String, Stock> existingByKey = Map.of("010620:KOSPI", existing);

            entryWriter.upsertOne(resolved, existingByKey, new WatchlistWriter.Counter());

            assertThat(existing.isActive()).isFalse();
            assertThat(existing.getDelistedAt()).isEqualTo(originalDelistedAt);
        }

        @Test
        @DisplayName(
                "StockInfo 없음(조회 실패) — 상폐/거래정지 판정 미수행, 직전 active/delistedAt 상태 유지"
                        + " (REQ-WLSYNC-150)")
        void stockInfoNull_preservesPriorState() {
            setUp();
            Stock existing = existingStock(false, LocalDate.of(2025, 12, 15));
            when(stockRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, null);
            Map<String, Stock> existingByKey = Map.of("010620:KOSPI", existing);

            entryWriter.upsertOne(resolved, existingByKey, new WatchlistWriter.Counter());

            assertThat(existing.isActive()).isFalse();
            assertThat(existing.getDelistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName("거래정지 해제 후 정상 재감지 — active=true로 복구(가역)")
        void haltedStock_normalDetected_recoversActive() {
            setUp();
            Stock existing = existingStock(false, null);
            when(stockRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
            StockInfo info =
                    new StockInfo(
                            AssetType.STOCK,
                            null,
                            null,
                            null,
                            Market.KOSPI,
                            ListingStatus.NORMAL,
                            null);
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, info);
            Map<String, Stock> existingByKey = Map.of("010620:KOSPI", existing);

            entryWriter.upsertOne(resolved, existingByKey, new WatchlistWriter.Counter());

            assertThat(existing.isActive()).isTrue();
            assertThat(existing.getDelistedAt()).isNull();
        }

        @Test
        @DisplayName("재조회(findById) 결과 없음(동시 삭제 추정) — WARN 후 skip, 예외 미발생 (REQ-WLSYNC-157)")
        void managedEntityNotFound_skipsWithoutException() {
            setUp();
            Stock existing = existingStock(true, null);
            when(stockRepository.findById(existing.getId())).thenReturn(Optional.empty());
            ResolvedStock resolved = new ResolvedStock("010620", "HD현대미포", Market.KOSPI, null);
            Map<String, Stock> existingByKey = Map.of("010620:KOSPI", existing);
            WatchlistWriter.Counter counter = new WatchlistWriter.Counter();

            Long touchedId = entryWriter.upsertOne(resolved, existingByKey, counter);

            assertThat(touchedId).isEqualTo(existing.getId());
            assertThat(counter.updated).isZero();
            assertThat(counter.unchanged).isZero();
            verify(etfMetadataWriter, never()).upsert(any(), any());
        }
    }
}
