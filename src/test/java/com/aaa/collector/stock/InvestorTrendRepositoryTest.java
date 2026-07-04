package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.RootFixtureCleaner;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

// SPEC-COLLECTOR-DBGRANT-003 M2-T1: 동시성 나머지 테스트(Concurrency)가 별도 Virtual Thread
// 커넥션으로 실제 COMMIT하므로 클래스 레벨 @Transactional 롤백으로는 격리되지 않는다(D6 롤백
// 비호환 분류). 공유 컨테이너 전환 후 잔여 행이 다른 테스트 클래스와 충돌하지 않도록 매 테스트
// 종료 시 root fixture 정리로 처리한다.
//
// M2-T4(REQ-DBGRANT3-013 동일 패턴): 정리가 앱 datasource(jdbcTemplate)를 그대로 썼던 것을
// RootFixtureCleaner.rootJdbcTemplate() 기반 root 커넥션으로 교체했다 — 앱 datasource가
// collector 계정(DELETE 권한 없음)으로 전환된 뒤에도 정리가 계속 동작해야 하기 때문이다(M2-T2
// grant 훅 도입 직후 실측: 기존 app-datasource DELETE가 1142에 상응하는 오류로 실패하며 잔여
// 행이 다른 테스트 클래스와 충돌).
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("InvestorTrendRepository 통합 테스트 (멱등 upsert)")
@Tag("integration")
class InvestorTrendRepositoryTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private InvestorTrendRepository investorTrendRepository;
    @Autowired private StockRepository stockRepository;

    private final List<Long> createdStockIds = new ArrayList<>();

    @AfterEach
    void cleanUpResidualRows() {
        JdbcTemplate rootJdbcTemplate = RootFixtureCleaner.rootJdbcTemplate(MYSQL.getJdbcUrl());
        for (Long stockId : createdStockIds) {
            rootJdbcTemplate.update("DELETE FROM investor_trend WHERE stock_id = ?", stockId);
            rootJdbcTemplate.update("DELETE FROM stocks WHERE id = ?", stockId);
        }
        createdStockIds.clear();
    }

    private Stock savedStock(String symbol) {
        Stock stock =
                stockRepository.save(
                        Stock.builder()
                                .symbol(symbol)
                                .nameKo("테스트종목_" + symbol)
                                .market(Market.KOSPI)
                                .assetType(AssetType.STOCK)
                                .listedDate(LocalDate.of(2015, 1, 1))
                                .build());
        createdStockIds.add(stock.getId());
        return stock;
    }

    private void insert(Stock stock, LocalDate date, long totalTradingValue) {
        investorTrendRepository.insertIgnoreDuplicate(
                InvestorTrend.builder()
                        .stock(stock)
                        .tradeDate(date)
                        .foreignNetQty(1000L)
                        .institutionNetQty(2000L)
                        .individualNetQty(3000L)
                        .foreignNetValue(10L)
                        .institutionNetValue(20L)
                        .individualNetValue(30L)
                        .totalVolume(5_000_000L)
                        .totalTradingValue(totalTradingValue)
                        .build());
    }

    @Nested
    @DisplayName("insertIgnoreDuplicate — 멱등 삽입 (REQ-BATCH2-021, -024, AC-8)")
    class InsertIgnoreDuplicate {

        @Test
        @DisplayName("신규 행 삽입 — 1개 저장됨")
        void newRow_insertsOne() {
            Stock stock = savedStock("005930");

            insert(stock, LocalDate.of(2026, 6, 5), 375_000_000_000L);

            assertThat(investorTrendRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("동일 (stock_id, trade_date) 중복 삽입 — 행 수 불변, UPDATE 미발생")
        void duplicate_rowCountUnchanged_noUpdate() {
            // Arrange
            Stock stock = savedStock("000660");
            LocalDate date = LocalDate.of(2026, 6, 5);
            long originalValue = 375_000_000_000L;
            insert(stock, date, originalValue);

            // Act — 동일 키, 다른 값으로 재삽입
            insert(stock, date, 999_999_999_999L);

            // Assert — 행 수 1 유지 + 최초 값 보존(UPDATE 미발생)
            assertThat(investorTrendRepository.countByStockId(stock.getId())).isEqualTo(1L);
            InvestorTrend saved = findByStock(stock.getId());
            assertThat(saved.getTotalTradingValue()).isEqualTo(originalValue);
        }

        @Test
        @DisplayName("서로 다른 거래일 — 각각 독립 삽입")
        void differentDates_insertsDistinctRows() {
            Stock stock = savedStock("035420");

            insert(stock, LocalDate.of(2026, 6, 4), 1L);
            insert(stock, LocalDate.of(2026, 6, 5), 2L);

            assertThat(investorTrendRepository.countByStockId(stock.getId())).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("동시성 — 5키 병렬 동일 키 동시 기록 (REQ-BATCH2-024, AC-3 S3-5)")
    class Concurrency {

        @Test
        @DisplayName("동일 (stock_id, trade_date)를 5스레드 동시 기록 — 중복 미생성, 예외 미중단")
        void fiveThreadsSameKey_noDuplicate_noException() throws InterruptedException {
            // Arrange
            Stock stock = savedStock("051910");
            LocalDate date = LocalDate.of(2026, 6, 5);

            // Act — 5개 Virtual Thread가 동일 키를 동시에 멱등 삽입
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 5; i++) {
                    long value = (i + 1) * 1000L;
                    executor.submit(() -> insert(stock, date, value));
                }
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            // Assert — DB uk 제약 + ON DUPLICATE KEY 원자성으로 행 수 1 유지
            assertThat(investorTrendRepository.countByStockId(stock.getId())).isEqualTo(1L);
        }
    }

    private InvestorTrend findByStock(Long stockId) {
        List<InvestorTrend> rows = investorTrendRepository.findAll();
        return rows.stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .findFirst()
                .orElseThrow();
    }
}
