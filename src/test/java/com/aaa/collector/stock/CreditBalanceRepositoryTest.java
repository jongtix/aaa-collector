package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("CreditBalanceRepository 통합 테스트 (멱등 upsert)")
@Tag("integration")
class CreditBalanceRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @Autowired private CreditBalanceRepository creditBalanceRepository;
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

    private void insert(Stock stock, LocalDate date, long loanBalanceAmt) {
        BigDecimal rate = new BigDecimal("1.5000");
        creditBalanceRepository.insertIgnoreDuplicate(
                CreditBalance.builder()
                        .stock(stock)
                        .tradeDate(date)
                        .loanNewQty(100L)
                        .loanRepayQty(50L)
                        .loanBalanceQty(1000L)
                        .loanNewAmt(700L)
                        .loanRepayAmt(350L)
                        .loanBalanceAmt(loanBalanceAmt)
                        .loanBalanceRate(rate)
                        .loanSupplyRate(rate)
                        .lendNewQty(10L)
                        .lendRepayQty(5L)
                        .lendBalanceQty(100L)
                        .lendNewAmt(70L)
                        .lendRepayAmt(35L)
                        .lendBalanceAmt(700L)
                        .lendBalanceRate(rate)
                        .lendSupplyRate(rate)
                        .build());
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH2-021, -024)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("005930");

            insert(stock, LocalDate.of(2026, 6, 5), 7000L);

            assertThat(creditBalanceRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate date = LocalDate.of(2026, 6, 5);
            long originalAmt = 7000L;
            insert(stock, date, originalAmt);

            // Act
            insert(stock, date, 999_999L);

            // Assert
            assertThat(creditBalanceRepository.countByStockId(stock.getId())).isEqualTo(1L);
            CreditBalance saved = findByStock(stock.getId());
            assertThat(saved.getLoanBalanceAmt()).isEqualTo(originalAmt);
        }

        @Test
        @DisplayName("서로 다른 거래일 — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            Stock stock = savedStock("035420");

            insert(stock, LocalDate.of(2026, 6, 4), 1L);
            insert(stock, LocalDate.of(2026, 6, 5), 2L);

            assertThat(creditBalanceRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }

    private CreditBalance findByStock(Long stockId) {
        List<CreditBalance> rows = creditBalanceRepository.findAll();
        return rows.stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .findFirst()
                .orElseThrow();
    }
}
