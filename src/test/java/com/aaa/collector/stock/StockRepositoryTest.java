package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("StockRepository 통합 테스트")
class StockRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

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
        @DisplayName("기존 findAllActive()는 무필터 — STOCK 외 자산도 포함(메서드 동작 보존)")
        void findAllActive_unchanged_includesNonStock() {
            // Arrange
            Stock stock = savedStock("FAS_ACT_STOCK", AssetType.STOCK);
            Stock etf = savedStock("FAS_ACT_ETF", AssetType.ETF);

            // Act — 기존 무필터 메서드는 자산 유형을 제한하지 않는다(회귀 보존)
            List<Stock> result = stockRepository.findAllActive();

            // Assert
            List<Long> resultIds = result.stream().map(Stock::getId).toList();
            assertThat(resultIds).contains(stock.getId(), etf.getId());
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
}
