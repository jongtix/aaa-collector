package com.aaa.collector.stock.etf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.DailyOhlcvRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockGrade;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.grade.Grade;
import com.aaa.collector.stock.grade.GradeCacheRepository;
import com.aaa.collector.stock.grade.StockGradeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
@DisplayName("EtfRepresentativeService 단위 테스트")
class EtfRepresentativeServiceTest {

    @Mock private EtfMetadataRepository etfMetadataRepository;
    @Mock private StockGradeRepository stockGradeRepository;
    @Mock private DailyOhlcvRepository dailyOhlcvRepository;
    @Mock private EtfRepresentativeHistoryRepository historyRepository;
    @Mock private GradeCacheRepository gradeCacheRepository;

    private EtfRepresentativeService service;

    @BeforeEach
    void setUp() {
        service =
                new EtfRepresentativeService(
                        etfMetadataRepository,
                        stockGradeRepository,
                        dailyOhlcvRepository,
                        historyRepository,
                        gradeCacheRepository);
        // findByStock returns empty by default — service will call save() for new grades
        lenient().when(stockGradeRepository.findByStock(any())).thenReturn(Optional.empty());
    }

    // --- Fixture helpers ---

    private Stock buildStock(String symbol, LocalDate listedDate) {
        return Stock.builder()
                .symbol(symbol)
                .nameKo("ETF " + symbol)
                .market(Market.KOSPI)
                .assetType(AssetType.ETF)
                .listedDate(listedDate)
                .active(true)
                .build();
    }

    private EtfMetadata buildMeta(
            Stock stock, String indexCode, int leverage, boolean inverse, boolean hedged) {
        return EtfMetadata.builder()
                .stock(stock)
                .underlyingIndexCode(indexCode)
                .leverage(leverage)
                .inverse(inverse)
                .hedged(hedged)
                .trStop(false)
                .build();
    }

    private void stubStockId(Stock stock, long id) {
        ReflectionTestUtils.setField(stock, "id", id);
    }

    private void stubAdtv(Object[]... rows) {
        when(dailyOhlcvRepository.findAdtvByStockIds(anyList())).thenReturn(Arrays.asList(rows));
    }

    private Object[] adtvRow(long stockId, double adtv) {
        return new Object[] {stockId, adtv};
    }

    // ---

    @Nested
    @DisplayName("Scenario 1 — 정상 대표 선정")
    class Scenario1NormalRepresentativeSelection {

        @Test
        @DisplayName("ADTV 1위(E1)가 대표, E2·E3는 C 등급으로 강등되고 캐시도 갱신된다")
        void recalculate_highestAdtvBecomesRepresentative_othersDowngradedToC() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2020, 2, 1));
            Stock e3 = buildStock("E3", LocalDate.of(2020, 3, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);
            stubStockId(e3, 3L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);
            EtfMetadata m3 = buildMeta(e3, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2, m3));
            when(dailyOhlcvRepository.countByStockId(anyLong()))
                    .thenReturn(25L); // all have >= 20 days
            stubAdtv(adtvRow(1L, 3000.0), adtvRow(2L, 2000.0), adtvRow(3L, 1000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E1 gets A grade
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e1 && "A".equals(sg.getGrade())));
            // Assert: E2 and E3 get C grade
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e2 && "C".equals(sg.getGrade())));
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e3 && "C".equals(sg.getGrade())));
            // Assert: cache updated for E2 and E3 (history insert verified in Scenario 3)
            verify(gradeCacheRepository).save(eq("E2"), eq(Grade.C), any(ZonedDateTime.class));
            verify(gradeCacheRepository).save(eq("E3"), eq(Grade.C), any(ZonedDateTime.class));
        }
    }

    @Nested
    @DisplayName("Scenario 2 — 거래정지 제외")
    class Scenario2TradingHaltExclusion {

        @Test
        @DisplayName("tr_stop=true인 E1은 후보 제외, ADTV 2위 E2가 대표가 된다")
        void recalculate_trStopExcludesStock_secondBecomesRepresentative() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2020, 2, 1));
            Stock e3 = buildStock("E3", LocalDate.of(2020, 3, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);
            stubStockId(e3, 3L);

            // E1 has tr_stop=true
            EtfMetadata m1 =
                    EtfMetadata.builder()
                            .stock(e1)
                            .underlyingIndexCode("069500")
                            .leverage(1)
                            .inverse(false)
                            .hedged(false)
                            .trStop(true)
                            .build();
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);
            EtfMetadata m3 = buildMeta(e3, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2, m3));
            when(dailyOhlcvRepository.countByStockId(eq(2L))).thenReturn(25L);
            when(dailyOhlcvRepository.countByStockId(eq(3L))).thenReturn(25L);
            stubAdtv(adtvRow(2L, 2000.0), adtvRow(3L, 1000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E2 is representative (A grade)
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e2 && "A".equals(sg.getGrade())));
            // Assert: E3 demoted to C
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e3 && "C".equals(sg.getGrade())));
            // Assert: E1 grade NOT touched (excluded from candidacy)
            verify(stockGradeRepository, never()).save(argThat(sg -> sg.getStock() == e1));
        }
    }

    @Nested
    @DisplayName("Scenario 3 — history INSERT")
    class Scenario3HistoryInsert {

        @Test
        @DisplayName("대표가 변경되면 history에 1행 INSERT (prev_stock_id 포함)")
        void recalculate_representativeChanged_insertsHistory() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2020, 2, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2));
            when(dailyOhlcvRepository.countByStockId(anyLong())).thenReturn(25L);
            // E2 has higher ADTV this cycle (E1 was previous representative)
            stubAdtv(adtvRow(1L, 1000.0), adtvRow(2L, 3000.0));

            // Previous representative was E1
            EtfRepresentativeHistory prevHistory =
                    EtfRepresentativeHistory.builder()
                            .groupKey("KOSPI:069500:1:NORMAL:false")
                            .stock(e1)
                            .prevStock(null)
                            .effectiveFrom(LocalDateTime.now().minusWeeks(1))
                            .build();
            when(historyRepository.findLatestByGroupKey(anyString()))
                    .thenReturn(Optional.of(prevHistory));

            // Act
            service.recalculate();

            // Assert: new history record inserted with E2 as representative
            ArgumentCaptor<EtfRepresentativeHistory> captor =
                    ArgumentCaptor.forClass(EtfRepresentativeHistory.class);
            verify(historyRepository).save(captor.capture());
            EtfRepresentativeHistory saved = captor.getValue();
            assertThat(saved.getStock().getSymbol()).isEqualTo("E2");
            assertThat(saved.getPrevStock().getSymbol()).isEqualTo("E1");
        }

        @Test
        @DisplayName("대표가 동일하면 history INSERT 없음")
        void recalculate_sameRepresentative_noHistoryInsert() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2020, 2, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2));
            when(dailyOhlcvRepository.countByStockId(anyLong())).thenReturn(25L);
            // E1 still has highest ADTV
            stubAdtv(adtvRow(1L, 3000.0), adtvRow(2L, 1000.0));

            // Previous representative was also E1
            EtfRepresentativeHistory prevHistory =
                    EtfRepresentativeHistory.builder()
                            .groupKey("KOSPI:069500:1:NORMAL:false")
                            .stock(e1)
                            .prevStock(null)
                            .effectiveFrom(LocalDateTime.now().minusWeeks(1))
                            .build();
            when(historyRepository.findLatestByGroupKey(anyString()))
                    .thenReturn(Optional.of(prevHistory));

            // Act
            service.recalculate();

            // Assert: no history insert (representative unchanged)
            verify(historyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Scenario 4 — 신규 ETF 유예 (< 20 거래일)")
    class Scenario4NewEtfGracePeriod {

        @Test
        @DisplayName("거래이력 15일인 E2는 후보 제외, E1만 대표 선정")
        void recalculate_insufficientTradingDays_excludedFromCandidacy() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2024, 1, 1)); // recently listed
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2));
            when(dailyOhlcvRepository.countByStockId(eq(1L))).thenReturn(25L);
            when(dailyOhlcvRepository.countByStockId(eq(2L))).thenReturn(15L); // < 20
            stubAdtv(adtvRow(1L, 2000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E1 gets A (sole representative)
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e1 && "A".equals(sg.getGrade())));
            // Assert: E2 grade NOT changed (excluded from candidacy)
            verify(stockGradeRepository, never()).save(argThat(sg -> sg.getStock() == e2));
            verify(gradeCacheRepository, never()).save(eq("E2"), any(Grade.class), any());
        }
    }

    @Nested
    @DisplayName("Scenario 5 — Tie-breaker")
    class Scenario5Tiebreaker {

        @Test
        @DisplayName("ADTV 동률 시 listedDate 빠른 E1이 대표")
        void recalculate_adtvTie_earlierListedDateWins() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1)); // listed earlier
            Stock e2 = buildStock("E2", LocalDate.of(2021, 1, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2));
            when(dailyOhlcvRepository.countByStockId(anyLong())).thenReturn(25L);
            // Same ADTV
            stubAdtv(adtvRow(1L, 1000.0), adtvRow(2L, 1000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E1 is representative
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e1 && "A".equals(sg.getGrade())));
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e2 && "C".equals(sg.getGrade())));
        }

        @Test
        @DisplayName("ADTV + listedDate 동률 시 symbol 사전순 빠른 종목이 대표")
        void recalculate_adtvAndDateTie_lexicographicallyEarlierSymbolWins() {
            // Arrange: same ADTV, same listed date → symbol tie-breaker
            LocalDate sameDate = LocalDate.of(2020, 1, 1);
            Stock ea = buildStock("AA500", sameDate);
            Stock eb = buildStock("BB500", sameDate);
            stubStockId(ea, 1L);
            stubStockId(eb, 2L);

            EtfMetadata ma = buildMeta(ea, "069500", 1, false, false);
            EtfMetadata mb = buildMeta(eb, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(ma, mb));
            when(dailyOhlcvRepository.countByStockId(anyLong())).thenReturn(25L);
            stubAdtv(adtvRow(1L, 1000.0), adtvRow(2L, 1000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: AA500 wins (lexicographically earlier)
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == ea && "A".equals(sg.getGrade())));
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == eb && "C".equals(sg.getGrade())));
        }
    }

    @Nested
    @DisplayName("Scenario 6 — ETF etf_metadata 없음")
    class Scenario6MissingEtfMetadata {

        @Test
        @DisplayName("etf_metadata 없는 ETF는 대표 선정 제외 — 등급 변경 없음")
        void recalculate_noMetadata_stockExcludedAndGradeUnchanged() {
            // Arrange: findAllWithStock returns only stocks WITH metadata.
            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of());

            // Act
            service.recalculate();

            // Assert: no grade changes
            verify(stockGradeRepository, never()).save(any(StockGrade.class));
            verify(historyRepository, never()).save(any());
            verify(gradeCacheRepository, never()).save(anyString(), any(Grade.class), any());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("단독 ETF 그룹: 1개만 있으면 대표 선정, 강등 없음")
        void recalculate_singleEtfInGroup_becomesRepresentativeNoDemotion() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            stubStockId(e1, 1L);

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1));
            when(dailyOhlcvRepository.countByStockId(anyLong())).thenReturn(25L);
            stubAdtv(adtvRow(1L, 2000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E1 gets A exactly once, never C
            verify(stockGradeRepository, times(1))
                    .save(argThat(sg -> sg.getStock() == e1 && "A".equals(sg.getGrade())));
            verify(stockGradeRepository, never())
                    .save(argThat(sg -> sg.getStock() == e1 && "C".equals(sg.getGrade())));
        }

        @Test
        @DisplayName("watchlist_removed_at 설정된 종목은 후보 제외")
        void recalculate_watchlistRemovedStock_excluded() {
            // Arrange
            Stock e1 = buildStock("E1", LocalDate.of(2020, 1, 1));
            Stock e2 = buildStock("E2", LocalDate.of(2020, 2, 1));
            stubStockId(e1, 1L);
            stubStockId(e2, 2L);

            // Set watchlist_removed_at on e1 (normally set by JPA/business logic)
            ReflectionTestUtils.setField(
                    e1, "watchlistRemovedAt", LocalDateTime.now().minusDays(1));

            EtfMetadata m1 = buildMeta(e1, "069500", 1, false, false);
            EtfMetadata m2 = buildMeta(e2, "069500", 1, false, false);

            when(etfMetadataRepository.findAllWithStock()).thenReturn(List.of(m1, m2));
            when(dailyOhlcvRepository.countByStockId(eq(2L))).thenReturn(25L);
            stubAdtv(adtvRow(2L, 2000.0));
            when(historyRepository.findLatestByGroupKey(anyString())).thenReturn(Optional.empty());

            // Act
            service.recalculate();

            // Assert: E2 is representative, E1 not touched
            verify(stockGradeRepository)
                    .save(argThat(sg -> sg.getStock() == e2 && "A".equals(sg.getGrade())));
            verify(stockGradeRepository, never()).save(argThat(sg -> sg.getStock() == e1));
        }
    }
}
