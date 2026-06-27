package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * cron 진입부 lazy 시더 통합 테스트 (SPEC-COLLECTOR-BACKFILL-001 T2, AC-8.1~8.5/AC-7.3/AC-7.4).
 *
 * <p>Testcontainers {@code mysql:8.4}로 native INSERT IGNORE 의미(중복 무해·기존 행 보존)와 시장별 data_table 분기를
 * 검증한다(H2는 INSERT IGNORE 미재현).
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("BackfillStatusSeeder 통합 테스트 (cron 진입부 lazy 시딩)")
class BackfillStatusSeederTest {

    private static final List<String> DOMESTIC_TABLES =
            List.of("daily_ohlcv", "investor_trend", "short_sale_domestic", "credit_balance");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @Autowired private BackfillStatusSeeder seeder;
    @Autowired private BackfillStatusRepository backfillStatusRepository;
    @Autowired private StockRepository stockRepository;

    @BeforeEach
    void cleanUp() {
        // 공유 Testcontainers MySQL — 테스트 간 격리.
        backfillStatusRepository.deleteAllInBatch();
        stockRepository.deleteAllInBatch();
    }

    private Stock saveStock(String symbol, Market market) {
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private Set<String> dataTablesOf(String targetCode) {
        return backfillStatusRepository.findAll().stream()
                .filter(s -> targetCode.equals(s.getTargetCode()))
                .map(BackfillStatus::getDataTable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Nested
    @DisplayName("AC-8.1 국내 종목 시딩 — 4개 data_table 행 (PENDING/NULL)")
    class DomesticSeeding {

        @Test
        @DisplayName(
                "행이 없던 국내 종목 → daily_ohlcv·investor_trend·short_sale_domestic·credit_balance 4행 생성")
        void domesticStock_seedsFourTables() {
            saveStock("005930", Market.KOSPI);

            seeder.seedActiveStocks();

            assertThat(dataTablesOf("005930")).containsExactlyInAnyOrderElementsOf(DOMESTIC_TABLES);
        }

        @Test
        @DisplayName("시딩된 행은 status=PENDING·last_collected_date=NULL·stale_count=0·attempt_count=0")
        void seededRow_hasPendingInitialState() {
            saveStock("000660", Market.KOSDAQ);

            seeder.seedActiveStocks();

            BackfillStatus row =
                    backfillStatusRepository.findAll().stream()
                            .filter(s -> "daily_ohlcv".equals(s.getDataTable()))
                            .findFirst()
                            .orElseThrow();
            assertThat(row.getStatus()).isEqualTo(BackfillStatusType.PENDING);
            assertThat(row.getLastCollectedDate()).isNull();
            assertThat(row.getStaleCount()).isZero();
            assertThat(row.getAttemptCount()).isZero();
        }
    }

    @Nested
    @DisplayName("AC-8.2/AC-7.3 미국 종목 시딩 — daily_ohlcv 1행만 (수급 3종 미대상)")
    class UsSeeding {

        @Test
        @DisplayName("미국 종목 → daily_ohlcv 1행만 생성, 수급 3종 미생성")
        void usStock_seedsOnlyDailyOhlcv() {
            saveStock("AAPL", Market.NASDAQ);

            seeder.seedActiveStocks();

            assertThat(dataTablesOf("AAPL")).containsExactly("daily_ohlcv");
        }
    }

    @Nested
    @DisplayName("AC-8.3 재시딩 무해 — 기존 진행 중 행 보존, 누락 행만 추가")
    class ReseedHarmless {

        @Test
        @DisplayName("진행 중(last_collected_date 설정) 행을 재시딩해도 status/last_collected_date 미변경")
        void existingInProgressRow_isPreserved() {
            saveStock("005930", Market.KOSPI);
            // Arrange — daily_ohlcv는 이미 IN_PROGRESS·과거 진행점 보유 (나머지 3종은 누락)
            backfillStatusRepository.saveAndFlush(
                    BackfillStatus.builder()
                            .targetType("STOCK")
                            .targetCode("005930")
                            .dataTable("daily_ohlcv")
                            .status(BackfillStatusType.IN_PROGRESS)
                            .lastCollectedDate(LocalDate.of(2024, 6, 10))
                            .build());

            // Act — 재시딩 (INSERT IGNORE → 기존 행 미접촉, 누락 3종만 생성)
            seeder.seedActiveStocks();

            // Assert — 기존 행 보존 + 누락 3종 추가 (총 4행)
            BackfillStatus existing =
                    backfillStatusRepository.findAll().stream()
                            .filter(s -> "daily_ohlcv".equals(s.getDataTable()))
                            .findFirst()
                            .orElseThrow();
            assertThat(existing.getStatus()).isEqualTo(BackfillStatusType.IN_PROGRESS);
            assertThat(existing.getLastCollectedDate()).isEqualTo(LocalDate.of(2024, 6, 10));
            assertThat(dataTablesOf("005930")).containsExactlyInAnyOrderElementsOf(DOMESTIC_TABLES);
        }
    }

    @Nested
    @DisplayName("AC-8.4 편입 종목 자동 시딩")
    class NewlyAddedStock {

        @Test
        @DisplayName("기존 종목 시딩 후 신규 종목 편입 → 다음 시딩 실행에서 자동 생성")
        void newlyActiveStock_isAutoSeededOnNextRun() {
            saveStock("005930", Market.KOSPI);
            seeder.seedActiveStocks();

            // Act — 신규 관심종목 편입 후 재실행
            saveStock("035420", Market.KOSPI);
            seeder.seedActiveStocks();

            // Assert — 신규 종목도 4행 생성됨
            assertThat(dataTablesOf("035420")).containsExactlyInAnyOrderElementsOf(DOMESTIC_TABLES);
        }
    }
}
