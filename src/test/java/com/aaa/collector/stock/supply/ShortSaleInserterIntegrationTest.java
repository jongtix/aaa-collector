package com.aaa.collector.stock.supply;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleDomestic;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("ShortSaleInserter 통합 테스트 (실 MySQL 경고 캡처, REQ-OBSV-023/AC-5)")
@Tag("integration")
class ShortSaleInserterIntegrationTest {

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

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

    private ShortSaleDomestic entity(Stock stock, LocalDate date) {
        return ShortSaleDomestic.builder()
                .stock(stock)
                .tradeDate(date)
                .shortSellQty(12_000L)
                .shortSellVolRate(new BigDecimal("3.5000"))
                .shortSellAmt(900_000_000L)
                .shortSellAmtRate(new BigDecimal("4.2000"))
                .shortSellAccQty(50_000L)
                .shortSellAccQtyRate(new BigDecimal("5.1000"))
                .shortSellAccAmt(3_750_000_000L)
                .shortSellAccAmtRate(new BigDecimal("6.3000"))
                .build();
    }

    private ShortSaleInserter buildInserter(SimpleMeterRegistry registry) {
        BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());
        return new ShortSaleInserter(new JdbcTemplate(dataSource), metrics);
    }

    @Test
    @DisplayName("신규 행 배치 삽입 — 행이 저장되고 침묵 드롭은 0이다")
    void insertsRowsWithoutSilentDrop() {
        // Arrange
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShortSaleInserter inserter = buildInserter(registry);
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
                                "SELECT COUNT(*) FROM short_sale_domestic WHERE stock_id = ?",
                                Integer.class,
                                stock.getId());
        assertThat(count).isEqualTo(2);
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("FK 위반(1452) 배치 삽입 — 행이 드롭되고 침묵 드롭 ≥1로 집계된다 (진짜 데이터 유실)")
    void genuineNonDuplicateDropIsCounted() {
        // Arrange — stocks에 존재하지 않는 stock_id로 INSERT IGNORE → FK 위반 1452가 경고로 강등되고 행이 드롭
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShortSaleInserter inserter = buildInserter(registry);
        Stock detached =
                Stock.builder()
                        .symbol("999999")
                        .nameKo("존재하지않는종목")
                        .market(Market.KOSPI)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build();
        ReflectionTestUtils.setField(detached, "id", 9_999_999L);

        // Act
        inserter.insertBatch(List.of(entity(detached, LocalDate.of(2026, 6, 5))));

        // Assert — 행은 드롭되어 0행, 침묵 드롭 카운터는 1062가 아닌 진짜 드롭으로 ≥1
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM short_sale_domestic WHERE stock_id = ?",
                                Integer.class,
                                9_999_999L);
        assertThat(count).isZero();
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("중복 키(1062) 배치 삽입 — 행 수 불변·침묵 드롭 미집계 (정상 멱등 중복)")
    void duplicateInsertDoesNotCountAsSilentDrop() {
        // Arrange
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ShortSaleInserter inserter = buildInserter(registry);
        Stock stock = savedStock("000660");
        LocalDate date = LocalDate.of(2026, 6, 5);

        // Act — 동일 키 2회 삽입(두 번째는 INSERT IGNORE로 1062 경고 발생)
        inserter.insertBatch(List.of(entity(stock, date)));
        inserter.insertBatch(List.of(entity(stock, date)));

        // Assert — 1행 유지, 1062는 침묵 드롭이 아니므로 카운터 0
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM short_sale_domestic WHERE stock_id = ?",
                                Integer.class,
                                stock.getId());
        assertThat(count).isEqualTo(1);
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isEqualTo(0.0);
    }
}
