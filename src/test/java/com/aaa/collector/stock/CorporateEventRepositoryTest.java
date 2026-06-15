package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.EventType;
import com.aaa.collector.stock.enums.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
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
@DisplayName("CorporateEventRepository 통합 테스트 (멱등 upsert)")
class CorporateEventRepositoryTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private CorporateEventRepository corporateEventRepository;
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

    private CorporateEvent buildDividend(Stock stock, LocalDate eventDate, Long cashAmount) {
        return CorporateEvent.builder()
                .stock(stock)
                .eventType(EventType.DIVIDEND)
                .eventDate(eventDate)
                .eventSubtype("결산배당")
                .payDate(LocalDate.of(2026, 8, 14))
                .stockPayDate(null)
                .oddPayDate(null)
                .cashAmount(cashAmount)
                .cashRate(new BigDecimal("0.5000"))
                .stockRate(new BigDecimal("0.0000"))
                .faceValue(100L)
                .stockKind("보통주")
                .highDividendFlag("N")
                .build();
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH3-053)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("005930");

            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2026, 6, 12), 361L));

            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, event_type, event_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate eventDate = LocalDate.of(2026, 6, 12);
            long originalAmt = 500L;
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, eventDate, originalAmt));

            // Act
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, eventDate, 999_999L));

            // Assert
            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CorporateEvent saved =
                    corporateEventRepository.findAll().stream()
                            .filter(e -> e.getStock().getId().equals(stock.getId()))
                            .findFirst()
                            .orElseThrow();
            assertThat(saved.getCashAmount()).isEqualTo(originalAmt);
        }

        @Test
        @DisplayName("서로 다른 event_date — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            Stock stock = savedStock("035420");

            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2025, 12, 28), 300L));
            corporateEventRepository.insertIgnoreDuplicate(
                    buildDividend(stock, LocalDate.of(2026, 6, 12), 361L));

            assertThat(corporateEventRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }
}
