package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("ShortSaleDomesticRepository 통합 테스트 (멱등 upsert)")
@Tag("integration")
class ShortSaleDomesticRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private ShortSaleDomesticRepository shortSaleDomesticRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private void insert(Stock stock, LocalDate date, long shortSellAmt) {
        BigDecimal rate = new BigDecimal("3.5000");
        shortSaleDomesticRepository.insertIgnoreDuplicate(
                ShortSaleDomestic.builder()
                        .stock(stock)
                        .tradeDate(date)
                        .shortSellQty(12_000L)
                        .shortSellVolRate(rate)
                        .shortSellAmt(shortSellAmt)
                        .shortSellAmtRate(rate)
                        .shortSellAccQty(50_000L)
                        .shortSellAccQtyRate(rate)
                        .shortSellAccAmt(3_750_000_000L)
                        .shortSellAccAmtRate(rate)
                        .build());
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH2-021, -024)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("005930");

            insert(stock, LocalDate.of(2026, 6, 5), 900_000_000L);

            assertThat(shortSaleDomesticRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate date = LocalDate.of(2026, 6, 5);
            long originalAmt = 900_000_000L;
            insert(stock, date, originalAmt);

            // Act
            insert(stock, date, 111_111_111L);

            // Assert
            assertThat(shortSaleDomesticRepository.countByStockId(stock.getId())).isEqualTo(1L);
            ShortSaleDomestic saved = findByStock(stock.getId());
            assertThat(saved.getShortSellAmt()).isEqualTo(originalAmt);
        }

        @Test
        @DisplayName("서로 다른 거래일 — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            Stock stock = savedStock("035420");

            insert(stock, LocalDate.of(2026, 6, 4), 1L);
            insert(stock, LocalDate.of(2026, 6, 5), 2L);

            assertThat(shortSaleDomesticRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }

    private ShortSaleDomestic findByStock(Long stockId) {
        List<ShortSaleDomestic> rows = shortSaleDomesticRepository.findAll();
        return rows.stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .findFirst()
                .orElseThrow();
    }
}
