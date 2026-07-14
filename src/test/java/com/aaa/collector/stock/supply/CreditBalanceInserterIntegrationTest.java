package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.observability.WatermarkMetrics;
import com.aaa.collector.stock.CreditBalance;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@Transactional
@DisplayName("CreditBalanceInserter 통합 테스트 (실 MySQL 경고 캡처, REQ-OBSV-023/AC-5)")
@Tag("integration")
class CreditBalanceInserterIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @Autowired private DataSource dataSource;
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

    private CreditBalance entity(Stock stock, LocalDate date) {
        return CreditBalance.builder()
                .stock(stock)
                .tradeDate(date)
                .loanNewQty(100L)
                .loanRepayQty(50L)
                .loanBalanceQty(1_000L)
                .loanNewAmt(700L)
                .loanRepayAmt(350L)
                .loanBalanceAmt(7_000L)
                .loanBalanceRate(new BigDecimal("1.5000"))
                .loanSupplyRate(new BigDecimal("2.5000"))
                .lendNewQty(10L)
                .lendRepayQty(5L)
                .lendBalanceQty(100L)
                .lendNewAmt(70L)
                .lendRepayAmt(35L)
                .lendBalanceAmt(700L)
                .lendBalanceRate(new BigDecimal("0.5000"))
                .lendSupplyRate(new BigDecimal("0.3000"))
                .build();
    }

    private CreditBalanceInserter buildInserter(SimpleMeterRegistry registry) {
        BatchMetrics metrics =
                new BatchMetrics(
                        registry, Clock.systemDefaultZone(), mock(BatchLastLoadRepository.class));
        WatermarkMetrics watermarkMetrics = new WatermarkMetrics(registry);
        return new CreditBalanceInserter(new JdbcTemplate(dataSource), metrics, watermarkMetrics);
    }

    @Test
    @DisplayName("신규 행 배치 삽입 — 행이 저장되고 침묵 드롭은 0이다")
    void insertsRowsWithoutSilentDrop() {
        // Arrange
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditBalanceInserter inserter = buildInserter(registry);
        Stock stock = savedStock("005930");

        // Act
        inserter.insertBatch(
                List.of(
                        entity(stock, LocalDate.of(2026, 6, 5)),
                        entity(stock, LocalDate.of(2026, 6, 8))));

        // Assert — 2행 저장, 침묵 드롭 0
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM credit_balance WHERE stock_id = ?",
                                Integer.class,
                                stock.getId());
        assertThat(count).isEqualTo(2);
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("중복 키(1062) 배치 삽입 — 행 수 불변·침묵 드롭 미집계 (정상 멱등 중복)")
    void duplicateInsertDoesNotCountAsSilentDrop() {
        // Arrange
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditBalanceInserter inserter = buildInserter(registry);
        Stock stock = savedStock("000660");
        LocalDate date = LocalDate.of(2026, 6, 5);

        // Act — 동일 키 2회 삽입(두 번째는 INSERT IGNORE로 1062 경고 발생)
        inserter.insertBatch(List.of(entity(stock, date)));
        inserter.insertBatch(List.of(entity(stock, date)));

        // Assert — 1행 유지, 1062는 침묵 드롭이 아니므로 카운터 0
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM credit_balance WHERE stock_id = ?",
                                Integer.class,
                                stock.getId());
        assertThat(count).isEqualTo(1);
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isEqualTo(0.0);
    }
}
