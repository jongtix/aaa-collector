package com.aaa.collector.stock.exthours;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
@DisplayName("ExtendedHoursRepository 통합 테스트 (멱등 INSERT IGNORE)")
@Tag("integration")
class ExtendedHoursRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private ExtendedHoursRepository extendedHoursRepository;
    @Autowired private StockRepository stockRepository;

    private Stock savedStock(String symbol) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.NYSE)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private void insert(Stock stock, Session session, LocalDate tradeDate, BigDecimal extPrice) {
        extendedHoursRepository.insertIgnoreDuplicate(
                stock.getId(),
                session.name(),
                tradeDate,
                extPrice,
                new BigDecimal("150.0000"),
                "YAHOO",
                LocalDateTime.of(2026, 6, 25, 10, 0, 0));
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-EXTH-041)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("동일 (stock_id, session, trade_date) 2회 삽입 — 1행 (멱등)")
        void duplicateKey_rowCountUnchanged() {
            // Arrange
            Stock stock = savedStock("AAPL");
            LocalDate tradeDate = LocalDate.of(2026, 6, 25);

            // Act
            insert(stock, Session.PRE, tradeDate, new BigDecimal("151.0000"));
            insert(stock, Session.PRE, tradeDate, new BigDecimal("999.0000"));

            // Assert
            assertThat(extendedHoursRepository.count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("다른 키(session 다름)로 삽입 — 2행")
        void differentKeys_insertDistinctRows() {
            // Arrange
            Stock stock = savedStock("MSFT");
            LocalDate tradeDate = LocalDate.of(2026, 6, 25);

            // Act
            insert(stock, Session.PRE, tradeDate, new BigDecimal("420.0000"));
            insert(stock, Session.AFTER, tradeDate, new BigDecimal("422.0000"));

            // Assert
            assertThat(extendedHoursRepository.count()).isEqualTo(2L);
        }
    }
}
