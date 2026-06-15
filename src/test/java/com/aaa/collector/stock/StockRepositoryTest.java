package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
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
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
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
}
