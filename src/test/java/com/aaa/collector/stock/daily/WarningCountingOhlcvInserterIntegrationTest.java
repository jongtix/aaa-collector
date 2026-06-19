package com.aaa.collector.stock.daily;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("WarningCountingOhlcvInserter 통합 테스트 (실 MySQL 경고 캡처, REQ-OBSV-023/AC-5)")
class WarningCountingOhlcvInserterIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

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

    private KisDailyOhlcvResponse.DailyOhlcvRow rowFor(LocalDate date) {
        return new KisDailyOhlcvResponse.DailyOhlcvRow(
                date.format(DATE_FMT),
                "75000",
                "74000",
                "76000",
                "73000",
                "1000000",
                "75000000000",
                "N");
    }

    private WarningCountingOhlcvInserter buildInserter(SimpleMeterRegistry registry) {
        BatchMetrics metrics = new BatchMetrics(registry, Clock.systemDefaultZone());
        return new WarningCountingOhlcvInserter(new JdbcTemplate(dataSource), metrics);
    }

    @Test
    @DisplayName("신규 행 배치 삽입 — 행이 저장되고 침묵 드롭은 0이다")
    void insertsRowsWithoutSilentDrop() {
        // Arrange
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WarningCountingOhlcvInserter inserter = buildInserter(registry);
        Stock stock = savedStock("005930");

        // Act
        inserter.insertBatch(
                stock.getId(),
                List.of(rowFor(LocalDate.of(2026, 6, 5)), rowFor(LocalDate.of(2026, 6, 8))),
                DATE_FMT);

        // Assert — 2행 저장, 침묵 드롭 0
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM daily_ohlcv WHERE stock_id = ?",
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
        WarningCountingOhlcvInserter inserter = buildInserter(registry);
        Stock stock = savedStock("000660");
        LocalDate date = LocalDate.of(2026, 6, 5);

        // Act — 동일 키 2회 삽입(두 번째는 INSERT IGNORE로 1062 경고 발생)
        inserter.insertBatch(stock.getId(), List.of(rowFor(date)), DATE_FMT);
        inserter.insertBatch(stock.getId(), List.of(rowFor(date)), DATE_FMT);

        // Assert — 1행 유지, 1062는 침묵 드롭이 아니므로 카운터 0
        Integer count =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT COUNT(*) FROM daily_ohlcv WHERE stock_id = ?",
                                Integer.class,
                                stock.getId());
        assertThat(count).isEqualTo(1);
        assertThat(registry.get("aaa_collector_batch_silent_drops_total").counter().count())
                .isEqualTo(0.0);
    }
}
