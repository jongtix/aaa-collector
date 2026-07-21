package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("StockRepository 통합 테스트")
@Tag("integration")
class StockRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol, AssetType assetType) {
        return savedStock(symbol, assetType, Market.KOSPI);
    }

    private Stock savedStock(String symbol, AssetType assetType, Market market) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(market)
                        .assetType(assetType)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .active(true) // 2축 필터 부활(SPEC-COLLECTOR-WLSYNC-008) — 이 fixture는 시장 유효 종목
                        .build());
    }

    @Nested
    @DisplayName("findAllActiveTradable — INDEX 제외 보장 (REQ-BATCH3-024)")
    class FindAllActiveTradable {

        @Test
        @DisplayName("STOCK·ETF·INDEX 활성 행 세 개 중 STOCK·ETF만 반환, INDEX 제외")
        void returnsStockAndEtf_excludesIndex() {
            // Arrange — 다른 테스트와 심볼 충돌 방지를 위해 고유 접두사 사용
            Stock stock = savedStock("TRAD_STOCK_001", AssetType.STOCK);
            Stock etf = savedStock("TRAD_ETF_001", AssetType.ETF);
            Stock index = savedStock("TRAD_IDX_001", AssetType.INDEX);

            // Act
            List<Stock> result = stockRepository.findAllActiveTradable();

            // Assert — STOCK·ETF 포함, INDEX 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(stock.getId(), etf.getId());
            assertThat(resultIds).doesNotContain(index.getId());
            assertThat(result.stream().map(Stock::getAssetType).toList())
                    .doesNotContain(AssetType.INDEX);
        }

        @Test
        @DisplayName("INDEX만 존재할 때 다른 TRADABLE 없으면 INDEX 반환 안 됨")
        void indexNotReturnedByTradable() {
            // Arrange
            Stock index = savedStock("TRAD_IDX_002", AssetType.INDEX);

            // Act
            List<Stock> result = stockRepository.findAllActiveTradable();

            // Assert — INDEX는 findAllActiveTradable 결과에 포함되지 않음
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).doesNotContain(index.getId());
            assertThat(result.stream().map(Stock::getAssetType).toList())
                    .doesNotContain(AssetType.INDEX);
        }

        @Test
        @DisplayName(
                "STOCK·ETF·ETN·COMMODITY·INDEX 활성 행 다섯 개 중 ETN·COMMODITY 포함, INDEX만 제외"
                        + " (SPEC-COLLECTOR-ASSETSCOPE-001 REQ-ASSETSCOPE-001·003, AC-1·AC-2)")
        void returnsEtnAndCommodity_excludesIndex() {
            // Arrange
            Stock stock = savedStock("TRAD_STOCK_002", AssetType.STOCK);
            Stock etf = savedStock("TRAD_ETF_002", AssetType.ETF);
            Stock etn = savedStock("TRAD_ETN_001", AssetType.ETN);
            Stock commodity = savedStock("TRAD_COM_001", AssetType.COMMODITY);
            Stock index = savedStock("TRAD_IDX_003", AssetType.INDEX);

            // Act
            List<Stock> result = stockRepository.findAllActiveTradable();

            // Assert — STOCK·ETF·ETN·COMMODITY 포함, INDEX만 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds)
                    .contains(stock.getId(), etf.getId(), etn.getId(), commodity.getId());
            assertThat(resultIds).doesNotContain(index.getId());
            assertThat(result.stream().map(Stock::getAssetType).toList())
                    .doesNotContain(AssetType.INDEX);
        }
    }

    @Nested
    @DisplayName(
            "findAllActiveStock — STOCK-only 한정 (SPEC-COLLECTOR-BATCH-004 REQ-BATCH4-013, AC-PATH-4)")
    class FindAllActiveStock {

        @Test
        @DisplayName("STOCK·ETF·INDEX·ETN·COMMODITY 혼재 — STOCK만 반환, 나머지 전부 제외")
        void returnsOnlyStock_excludesOthers() {
            // Arrange — 고유 접두사로 다른 테스트와 심볼 충돌 방지
            Stock stock = savedStock("FAS_STOCK_001", AssetType.STOCK);
            Stock etf = savedStock("FAS_ETF_001", AssetType.ETF);
            Stock index = savedStock("FAS_IDX_001", AssetType.INDEX);
            Stock etn = savedStock("FAS_ETN_001", AssetType.ETN);
            Stock commodity = savedStock("FAS_COM_001", AssetType.COMMODITY);

            // Act
            List<Stock> result = stockRepository.findAllActiveStock();

            // Assert — STOCK 포함, ETF/INDEX/ETN/COMMODITY 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(stock.getId());
            assertThat(resultIds)
                    .doesNotContain(etf.getId(), index.getId(), etn.getId(), commodity.getId());
            assertThat(result.stream().map(Stock::getAssetType).toList())
                    .containsOnly(AssetType.STOCK);
        }

        @Test
        @DisplayName("watchlistRemovedAt 설정된 STOCK 행 제외 — 활성 STOCK만 반환")
        void excludesWatchlistRemovedStock() {
            // Arrange
            Stock active = savedStock("FAS_STOCK_ACT", AssetType.STOCK);
            Stock removed = savedStock("FAS_STOCK_REM", AssetType.STOCK);
            stockRepository.markWatchlistRemoved(Set.of(removed.getId()));

            // Act
            List<Stock> result = stockRepository.findAllActiveStock();

            // Assert
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(active.getId());
            assertThat(resultIds).doesNotContain(removed.getId());
        }

        @Test
        @DisplayName("기존 findAllActive()는 자산 유형 무필터 — STOCK 외 자산도 포함(메서드 동작 보존)")
        void findAllActive_unchanged_includesNonStock() {
            // Arrange
            Stock stock = savedStock("FAS_ACT_STOCK", AssetType.STOCK);
            Stock etf = savedStock("FAS_ACT_ETF", AssetType.ETF);

            // Act — 자산 유형은 제한하지 않는다(회귀 보존). 2축(active·watchlist) 필터는 두 fixture 모두
            // active=true·watchlistRemovedAt=null이므로 여전히 통과한다.
            List<Stock> result = stockRepository.findAllActive();

            // Assert
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(stock.getId(), etf.getId());
        }
    }

    @Nested
    @DisplayName(
            "findAllActive — 2축 직교 필터 (SPEC-COLLECTOR-WLSYNC-008 REQ-WLSYNC-141,148,149, AC-시나리오10)")
    class ActiveWatchlistTwoAxisFilter {

        @Test
        @DisplayName("active=false(상폐) 종목 A — findAllActive에서 제외, delisted_at 아닌 active로 제외됨")
        void delistedStock_excludedByActiveAxis() {
            // Arrange — 상폐 종목: active=false, delisted_at 설정(사유 메타데이터일 뿐 필터 축 아님)
            Stock delisted =
                    stockRepository.save(
                            Stock.builder()
                                    .symbol("TWO_AXIS_A")
                                    .nameKo("상폐종목")
                                    .market(Market.KOSPI)
                                    .assetType(AssetType.STOCK)
                                    .active(false)
                                    .delistedAt(LocalDate.of(2025, 12, 15))
                                    .build());
            Stock normal = savedStock("TWO_AXIS_C", AssetType.STOCK);

            // Act
            List<Stock> result = stockRepository.findAllActive();

            // Assert
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).doesNotContain(delisted.getId());
            assertThat(resultIds).contains(normal.getId());
        }

        @Test
        @DisplayName("watchlist_removed_at NOT NULL 종목 B — findAllActive에서 제외(기존 축 보존)")
        void watchlistRemovedStock_excludedByWatchlistAxis() {
            // Arrange
            Stock removed = savedStock("TWO_AXIS_B", AssetType.STOCK);
            stockRepository.markWatchlistRemoved(Set.of(removed.getId()));

            // Act
            List<Stock> result = stockRepository.findAllActive();

            // Assert
            assertThat(result.stream().map(Stock::getId).toList()).doesNotContain(removed.getId());
        }

        @Test
        @DisplayName(
                "REQ-WLSYNC-141 직교성 — active=false + watchlist_removed_at 동시(종목 D) 여전히 제외,"
                        + " 두 축은 독립 유지(단일 필드 병합 없음)")
        void bothAxesSet_stillExcluded_axesRemainIndependent() {
            // Arrange — 상폐이면서 관심그룹에서도 제거된 종목 D
            Stock both =
                    stockRepository.save(
                            Stock.builder()
                                    .symbol("TWO_AXIS_D")
                                    .nameKo("상폐+제거종목")
                                    .market(Market.KOSPI)
                                    .assetType(AssetType.STOCK)
                                    .active(false)
                                    .delistedAt(LocalDate.of(2025, 12, 15))
                                    .build());
            stockRepository.markWatchlistRemoved(Set.of(both.getId()));

            // Act
            List<Stock> result = stockRepository.findAllActive();

            // Assert — 제외됨(두 축 모두 배제 사유가 되지만 서로 병합되지 않고 독립적으로 반영됨)
            assertThat(result.stream().map(Stock::getId).toList()).doesNotContain(both.getId());
            Stock reloaded =
                    stockRepository.findBySymbolAndMarket("TWO_AXIS_D", Market.KOSPI).orElseThrow();
            assertThat(reloaded.isActive()).isFalse();
            assertThat(reloaded.getWatchlistRemovedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName(
            "findAllActiveOverseasTradable — 미국 STOCK+ETF 한정"
                    + " (SPEC-COLLECTOR-OVERSEAS-OHLCV-001 REQ-OVOH-001, AC-TGT-1)")
    class FindAllActiveOverseasTradable {

        @Test
        @DisplayName("미국 STOCK·ETF만 반환 — 미국 INDEX·국내 STOCK/ETF·제거 종목 제외")
        void returnsUsStockAndEtf_excludesUsIndexDomesticAndRemoved() {
            // Arrange
            Stock nyseStock = savedStock("OVS_NYSE_STK", AssetType.STOCK, Market.NYSE);
            Stock nasdaqStock = savedStock("OVS_NAS_STK", AssetType.STOCK, Market.NASDAQ);
            Stock amexEtf = savedStock("OVS_AMEX_ETF", AssetType.ETF, Market.AMEX);
            Stock usIndex = savedStock("OVS_US_IDX", AssetType.INDEX, Market.US);
            Stock domesticStock = savedStock("OVS_DOM_STK", AssetType.STOCK, Market.KOSPI);
            Stock domesticEtf = savedStock("OVS_DOM_ETF", AssetType.ETF, Market.KOSDAQ);
            Stock removedUsStock = savedStock("OVS_NYSE_REM", AssetType.STOCK, Market.NYSE);
            stockRepository.markWatchlistRemoved(Set.of(removedUsStock.getId()));

            // Act
            List<Stock> result = stockRepository.findAllActiveOverseasTradable();

            // Assert — 미국 STOCK·ETF만 포함, 그 외 전부 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(nyseStock.getId(), nasdaqStock.getId(), amexEtf.getId());
            assertThat(resultIds)
                    .doesNotContain(
                            usIndex.getId(),
                            domesticStock.getId(),
                            domesticEtf.getId(),
                            removedUsStock.getId());
        }

        @Test
        @DisplayName("기존 findAllActiveTradable()는 시장 무관 — 국내 종목도 포함(회귀 보존)")
        void findAllActiveTradable_unchanged_includesDomestic() {
            // Arrange
            Stock domestic = savedStock("OVS_REG_DOM", AssetType.STOCK, Market.KOSPI);
            Stock overseas = savedStock("OVS_REG_US", AssetType.STOCK, Market.NASDAQ);

            // Act — 시장 무관 메서드는 국내·미국 모두 포함(회귀 보존)
            List<Stock> result = stockRepository.findAllActiveTradable();

            // Assert
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(domestic.getId(), overseas.getId());
        }
    }

    @Nested
    @DisplayName("findAllActiveDomesticTradable — 국내 STOCK·ETF만 반환")
    class FindAllActiveDomesticTradable {

        @Test
        @DisplayName("KOSPI·KOSDAQ·KRX STOCK·ETF만 반환 — 미국 종목·INDEX·제거 종목 제외")
        void returnsDomesticStockAndEtf_excludesOverseasAndIndex() {
            // Arrange
            Stock kospiStock = savedStock("DOM_KPI_STK", AssetType.STOCK, Market.KOSPI);
            Stock kosdaqEtf = savedStock("DOM_KSQ_ETF", AssetType.ETF, Market.KOSDAQ);
            Stock krxIndex = savedStock("DOM_KRX_IDX", AssetType.INDEX, Market.KRX);
            Stock nasdaqStock = savedStock("DOM_NAS_STK", AssetType.STOCK, Market.NASDAQ);
            Stock removedKospi = savedStock("DOM_KPI_REM", AssetType.STOCK, Market.KOSPI);
            stockRepository.markWatchlistRemoved(Set.of(removedKospi.getId()));

            // Act
            List<Stock> result = stockRepository.findAllActiveDomesticTradable();

            // Assert — KOSPI·KOSDAQ STOCK·ETF만 포함, INDEX·미국·제거 종목 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(kospiStock.getId(), kosdaqEtf.getId());
            assertThat(resultIds)
                    .doesNotContain(krxIndex.getId(), nasdaqStock.getId(), removedKospi.getId());
        }

        @Test
        @DisplayName(
                "KOSPI ETN·COMMODITY 포함 — 국내 tradable에 신규 편입"
                        + " (SPEC-COLLECTOR-ASSETSCOPE-001 REQ-ASSETSCOPE-001, AC-1)")
        void returnsDomesticEtnAndCommodity() {
            // Arrange
            Stock etn = savedStock("DOM_KPI_ETN", AssetType.ETN, Market.KOSPI);
            Stock commodity = savedStock("DOM_KPI_COM", AssetType.COMMODITY, Market.KOSPI);
            Stock krxIndex = savedStock("DOM_KRX_IDX2", AssetType.INDEX, Market.KRX);

            // Act
            List<Stock> result = stockRepository.findAllActiveDomesticTradable();

            // Assert — ETN·COMMODITY 포함, INDEX 제외
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(etn.getId(), commodity.getId());
            assertThat(resultIds).doesNotContain(krxIndex.getId());
        }
    }
}
