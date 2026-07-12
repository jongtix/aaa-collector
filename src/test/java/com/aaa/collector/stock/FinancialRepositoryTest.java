package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.stock.enums.PeriodType;
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
@DisplayName("FinancialRepository 통합 테스트 (멱등 upsert, REQ-BATCH4-023)")
@Tag("integration")
class FinancialRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private FinancialRepository financialRepository;
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

    private void insert(
            Stock stock, PeriodType periodType, LocalDate periodDate, BigDecimal revenueGrowth) {
        financialRepository.insertIgnoreDuplicate(
                Financial.builder()
                        .stock(stock)
                        .periodType(periodType)
                        .periodDate(periodDate)
                        .revenueGrowth(revenueGrowth)
                        .operatingProfitGrowth(BigDecimal.ZERO)
                        .netIncomeGrowth(new BigDecimal("8.1"))
                        .roe(new BigDecimal("15.2"))
                        .eps(6993L)
                        .sps(57_655L)
                        .bps(71_907L)
                        .retentionRate(new BigDecimal("1200.5"))
                        .debtRatio(new BigDecimal("45.3"))
                        .build());
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (AC-FIN-4)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("FIN_005930");

            insert(stock, PeriodType.ANNUAL, LocalDate.of(2024, 3, 1), new BigDecimal("12.5"));

            assertThat(financialRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, period_type, period_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("FIN_000660");
            LocalDate date = LocalDate.of(2024, 3, 1);
            insert(stock, PeriodType.ANNUAL, date, new BigDecimal("12.5"));

            // Act — 동일 키, 다른 값으로 재삽입
            insert(stock, PeriodType.ANNUAL, date, new BigDecimal("99.9"));

            // Assert — 행 수 1 유지 + 최초 값 보존(UPDATE 미발생)
            assertThat(financialRepository.countByStockId(stock.getId())).isEqualTo(1L);
            Financial saved = findByStock(stock.getId());
            assertThat(saved.getRevenueGrowth()).isEqualByComparingTo("12.5");
        }

        @Test
        @DisplayName("동일 결산기·다른 period_type(ANNUAL/QUARTERLY) — 각각 독립 삽입")
        void differentPeriodType_insertsDistinctRows() {
            Stock stock = savedStock("FIN_035420");
            LocalDate date = LocalDate.of(2024, 3, 1);

            insert(stock, PeriodType.ANNUAL, date, new BigDecimal("12.5"));
            insert(stock, PeriodType.QUARTERLY, date, new BigDecimal("3.2"));

            assertThat(financialRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }

    private Financial findByStock(Long stockId) {
        List<Financial> rows = financialRepository.findAll();
        return rows.stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .findFirst()
                .orElseThrow();
    }
}
