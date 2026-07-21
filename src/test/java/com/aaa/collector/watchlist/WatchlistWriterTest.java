package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListService;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.etf.EtfMetaInfo;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WatchlistWriterTest {

    @Mock private StockRepository stockRepository;
    @Mock private StockListService stockListService;
    @Mock private com.aaa.collector.stock.etf.EtfMetadataWriter etfMetadataWriter;
    private WatchlistWriter watchlistWriter;

    @BeforeEach
    void setUp() {
        WatchlistEntryWriter entryWriter =
                new WatchlistEntryWriter(stockRepository, etfMetadataWriter);
        watchlistWriter = new WatchlistWriter(stockRepository, entryWriter, stockListService);
        // 캐시 갱신 경로(refreshCache)는 이 테스트의 관심 범위 밖이므로 lenient 스텁 처리
        lenient().doNothing().when(stockListService).refreshCache();
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
            assertThat(saved.getNameEn()).isEqualTo("Samsung ETF");
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
        @DisplayName(
                "비활성 기존 종목 + StockInfo 없음(조회 실패) — active 상태 유지, 판정 미수행"
                        + " (REQ-WLSYNC-150, SPEC-COLLECTOR-WLSYNC-008)")
        void upsertAll_inactiveStockWithoutStockInfo_preservesActiveState() {
            // Arrange — stockInfo=null(graceful skip)이면 상폐/거래정지 판정을 내리지 않고 직전 active 상태를
            // 그대로 유지한다. 과거(SPEC-COLLECTOR-WLSYNC-008 이전)에는 syncFromWatchlist가 무조건
            // active=true로 되돌렸으나, 이는 상폐 종목이 조회 실패 시에도 재활성화되는 근본 원인이었다.
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, false);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.isActive()).isFalse();
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName(
                "nameKo 변경 + 비활성 종목 + StockInfo 없음 — nameKo만 갱신, active는 유지" + " (REQ-WLSYNC-150)")
        void upsertAll_nameKoChangedAndInactiveWithoutStockInfo_onlyNameUpdated() {
            // Arrange
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자 (신)", Market.KOSPI, null);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", null, false);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getNameKo()).isEqualTo("삼성전자 (신)");
            assertThat(existing.isActive()).isFalse();
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
        @DisplayName("기존 종목 + StockInfo.market != 저장 시장 — 시장 교정됨, counter.updated 증가 (AC-4)")
        void upsertAll_existingStockMarketDiffersFromAuthoritativeInStockInfo_marketCorrected() {
            // Arrange — 기존 DB에 KOSPI로 저장, 권위 market은 KOSDAQ (mket_id_cd=KSQ)
            // resolved.market() = KOSPI (routing market, 이 키로 기존 종목 검색됨)
            // StockInfo.market() = KOSDAQ (parseDomestic이 확정한 권위값)
            StockInfo info = new StockInfo(AssetType.STOCK, "에코프로비엠", null, Market.KOSDAQ);
            ResolvedStock resolved = new ResolvedStock("247540", "에코프로비엠", Market.KOSPI, info);
            Stock existing = stockWith("247540", Market.KOSPI, "에코프로비엠", "EcoPro BM", true);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert — 시장이 KOSDAQ으로 교정됨 (AC-4), counter.updated 증가(1회 갱신)
            assertThat(existing.getMarket()).isEqualTo(Market.KOSDAQ);
            verify(stockRepository, never()).save(any());
        }

        @Test
        @DisplayName("UN-라우팅 변경 케이스는 in-place 교정 비대상 — M4 DB 재구성(C7)으로 해소 (AC-4 boundary)")
        void
                upsertAll_unRoutingChangedMarket_insertsNewRowAndSoftRemovesOldRow_relyingOnDbRebuild() {
            // 설계 의도: 구버그 KisMarketResolver는 fid="UN" → KOSDAQ으로 라우팅해
            // NAVER(035420)가 market=KOSDAQ으로 DB에 저장될 수 있었다.
            // 신규 KisMarketResolver는 fid="UN" → KOSPI(국내 coarse)로 라우팅하고,
            // mket_id_cd=STK 권위값도 KOSPI이므로 ResolvedStock.market()==KOSPI,
            // StockInfo.market()==KOSPI.
            //
            // 이 케이스에서 lookup 키는 "035420:KOSPI"이지만 DB에는 "035420:KOSDAQ"만 존재하므로
            // 키가 일치하지 않아 existing==null → INSERT 경로로 진입한다.
            // correctMetadata(in-place 교정)는 호출되지 않으며,
            // 기존 KOSDAQ 행은 touchedIds에 포함되지 않아 markWatchlistRemoved 대상이 된다.
            // 이 동작은 의도된 설계이며, DB 재구성(M4/C7) 단계에서 구버그 행이 정리된다.

            // Arrange
            StockInfo info = new StockInfo(AssetType.STOCK, "NAVER", null, Market.KOSPI);
            ResolvedStock resolved = new ResolvedStock("035420", "NAVER", Market.KOSPI, info);
            // 구버그 상태: 035420이 KOSDAQ으로 잘못 저장됨 (id=7)
            Stock existingKosdaq =
                    stockWith("035420", Market.KOSDAQ, "NAVER", "NAVER Corp", true, null, 7L);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existingKosdaq));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert — 신규 (035420, KOSPI) 행이 INSERT됨 (in-place 교정이 아님)
            ArgumentCaptor<Stock> saveCaptor = ArgumentCaptor.forClass(Stock.class);
            verify(stockRepository).save(saveCaptor.capture());
            Stock inserted = saveCaptor.getValue();
            assertThat(inserted.getSymbol()).isEqualTo("035420");
            assertThat(inserted.getMarket()).isEqualTo(Market.KOSPI);

            // Assert — 기존 (035420, KOSDAQ) 행(id=7)은 soft-remove됨
            verify(stockRepository).markWatchlistRemoved(Set.of(7L));

            // Assert — 기존 KOSDAQ 엔티티의 market은 변경되지 않음 (in-place 교정 비대상)
            assertThat(existingKosdaq.getMarket()).isEqualTo(Market.KOSDAQ);
        }

        @Test
        @DisplayName(
                "기존 종목 + StockInfo listedDate non-null + 저장 listedDate null — listedDate 채워짐 (AC-5)")
        void upsertAll_existingStockNullListedDate_filledByStockInfo() {
            // Arrange
            LocalDate authoritativeDate = LocalDate.of(2000, 1, 1);
            StockInfo info =
                    new StockInfo(AssetType.STOCK, "Samsung Electronics", authoritativeDate);
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, info);
            Stock existing = stockWith("005930", Market.KOSPI, "삼성전자", "Samsung Electronics", true);
            // existing.listedDate == null (stockWith에서 listedDate 미설정)
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert
            assertThat(existing.getListedDate()).isEqualTo(authoritativeDate);
        }

        @Test
        @DisplayName("기존 종목 + StockInfo listedDate non-null + 저장 listedDate already set — 덮어쓰기 없음")
        void upsertAll_existingStockWithListedDate_notOverwritten() {
            // Arrange
            LocalDate existingDate = LocalDate.of(2000, 1, 1);
            Stock existing =
                    Stock.builder()
                            .symbol("005930")
                            .nameKo("삼성전자")
                            .nameEn("Samsung Electronics")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .listedDate(existingDate)
                            .active(true)
                            .build();
            ReflectionTestUtils.setField(existing, "id", 1L);
            StockInfo info =
                    new StockInfo(AssetType.STOCK, "Samsung Electronics", LocalDate.of(2020, 5, 1));
            ResolvedStock resolved = new ResolvedStock("005930", "삼성전자", Market.KOSPI, info);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existing));

            // Act
            watchlistWriter.upsertAll(List.of(resolved), 0);

            // Assert — 기존 상장일 보존
            assertThat(existing.getListedDate()).isEqualTo(existingDate);
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

    @Nested
    @DisplayName("ETF 메타 저장 실패 — stock도 함께 롤백(skip)")
    class EtfMetadataSaveFailure {

        /**
         * REQUIRED 전파 변경 후 동작 검증: etfMetadataWriter.upsert 예외는 upsertOne 트랜잭션 전체를 롤백시키고,
         * WatchlistWriter.upsertAll이 DataAccessException을 잡아 해당 종목을 skip한다 (ADR-022 결정 3). 단위
         * 테스트에서는 트랜잭션 프록시가 없으므로 DataIntegrityViolationException이 upsertOne을 통해 upsertAll까지 전파되고
         * catch 블록에서 처리됨을 확인한다.
         */
        @Test
        @DisplayName("etfMetadataWriter.upsert 예외 — upsertAll이 DataAccessException을 잡아 해당 종목 skip")
        void upsertAll_etfMetaSaveFails_stockIsSkipped() {
            // Arrange
            EtfMetaInfo etfMeta = new EtfMetaInfo("069500", 1, false, false, false);
            StockInfo etfInfo =
                    new StockInfo(
                            AssetType.ETF, "KODEX 200", LocalDate.of(2002, 10, 14), etfMeta, null);
            ResolvedStock etfStock =
                    new ResolvedStock("069500", "KODEX 200", Market.KOSPI, etfInfo);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of());
            when(stockRepository.save(any()))
                    .thenAnswer(
                            inv -> {
                                Stock s = inv.getArgument(0);
                                ReflectionTestUtils.setField(s, "id", 99L);
                                return s;
                            });
            doThrow(new DataIntegrityViolationException("fk_etf_metadata_stock violated"))
                    .when(etfMetadataWriter)
                    .upsert(any(), any());

            // Act & Assert: DataIntegrityViolationException이 upsertAll 밖으로 전파되지 않음
            assertThatCode(() -> watchlistWriter.upsertAll(List.of(etfStock), 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ETF 메타 저장 실패 종목 — markWatchlistRemoved 오인 마킹 방지")
        void upsertAll_etfMetaSaveFails_failedStockNotMarkedRemoved() {
            // Arrange: 기존 DB에 ETF 종목(id=5) 있음. ETF 메타 저장 실패 → skip.
            // skip된 종목의 ID는 touchedIds에 추가되어 markWatchlistRemoved 대상에서 제외된다.
            EtfMetaInfo etfMeta = new EtfMetaInfo("069500", 1, false, false, false);
            StockInfo etfInfo =
                    new StockInfo(
                            AssetType.ETF, "KODEX 200", LocalDate.of(2002, 10, 14), etfMeta, null);
            Stock existingEtf =
                    stockWith("069500", Market.KOSPI, "KODEX 200", "KODEX 200", true, null, 5L);
            ResolvedStock etfStock =
                    new ResolvedStock("069500", "KODEX 200", Market.KOSPI, etfInfo);
            when(stockRepository.findAllBySymbolIn(any())).thenReturn(List.of(existingEtf));
            doThrow(new DataIntegrityViolationException("fk_etf_metadata_stock violated"))
                    .when(etfMetadataWriter)
                    .upsert(any(), any());

            // Act
            watchlistWriter.upsertAll(List.of(etfStock), 0);

            // Assert: 실패 종목(id=5)이 markWatchlistRemoved 대상이 되어서는 안 됨
            verify(stockRepository, never()).markWatchlistRemoved(any());
        }
    }
}
