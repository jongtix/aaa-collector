package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.RootFixtureCleaner;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * cron 진입부 lazy 시더 통합 테스트 (SPEC-COLLECTOR-BACKFILL-001 T2, AC-8.1~8.5/AC-7.3/AC-7.4).
 *
 * <p>Testcontainers {@code mysql:8.4}로 native INSERT IGNORE 의미(중복 무해·기존 행 보존)와 시장별 data_table 분기를
 * 검증한다(H2는 INSERT IGNORE 미재현).
 *
 * <p><b>M2-T1 격리 분류 — 싱글턴 공유 제외(전용 컨테이너)</b>. M2-T3(REQ-DBGRANT3-013)에서 {@code @BeforeEach} 정리를
 * {@link RootFixtureCleaner}의 root 커넥션으로 재배선했다 — 테스트 대상 코드 경로(시더·리포지토리 호출)는 앱 datasource를 그대로 사용.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("BackfillStatusSeeder 통합 테스트 (cron 진입부 lazy 시딩)")
@Tag("integration")
class BackfillStatusSeederTest {

    // SPEC-COLLECTOR-BACKFILL-007 W2 (REQ-BACKFILL-090): corporate_events 편입,
    // SPEC-COLLECTOR-BACKFILL-009 W1 (REQ-BACKFILL-143): corporate_events_dividend 편입 → 국내 6종
    private static final List<String> DOMESTIC_TABLES =
            List.of(
                    "daily_ohlcv",
                    "investor_trend",
                    "short_sale_domestic",
                    "credit_balance",
                    "corporate_events",
                    "corporate_events_dividend");

    // SPEC-COLLECTOR-OVERSEAS-SPLIT-001 REQ-OSPLIT-063: 미국 corporate_events 편입 → 2종
    private static final List<String> OVERSEAS_TABLES = List.of("daily_ohlcv", "corporate_events");

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @Autowired private BackfillStatusSeeder seeder;
    @Autowired private BackfillStatusRepository backfillStatusRepository;
    @Autowired private StockRepository stockRepository;

    @BeforeEach
    void cleanUp() throws SQLException {
        // root 커넥션으로 정리(M2-T3) — 테스트 간 격리.
        RootFixtureCleaner.deleteAllBackfillStatus(MYSQL.getJdbcUrl());
        RootFixtureCleaner.deleteAllStocks(MYSQL.getJdbcUrl());
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
    @DisplayName(
            "AC-8.1/AC-2 국내 종목 시딩 — 6개 data_table 행 (PENDING/NULL, corporate_events(_dividend) 포함)")
    class DomesticSeeding {

        @Test
        @DisplayName(
                "행이 없던 국내 종목 → daily_ohlcv·investor_trend·short_sale_domestic·credit_balance·corporate_events·corporate_events_dividend 6행 생성")
        void domesticStock_seedsSixTables() {
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
    @DisplayName("AC-13/AC-7.3 미국 종목 시딩 — daily_ohlcv+corporate_events 2행 (수급 3종 미대상)")
    class UsSeeding {

        @Test
        @DisplayName("REQ-OSPLIT-063: 미국 종목 → daily_ohlcv·corporate_events 2행 생성, 수급 3종 미생성")
        void usStock_seedsDailyOhlcvAndCorporateEvents() {
            saveStock("AAPL", Market.NASDAQ);

            seeder.seedActiveStocks();

            assertThat(dataTablesOf("AAPL")).containsExactlyInAnyOrderElementsOf(OVERSEAS_TABLES);
        }

        @Test
        @DisplayName("비회귀: 미국 종목은 수급 3종(investor_trend/short_sale_domestic/credit_balance) 미시딩")
        void usStock_doesNotSeedDomesticSupplyTables() {
            saveStock("TSLA", Market.NASDAQ);

            seeder.seedActiveStocks();

            assertThat(dataTablesOf("TSLA"))
                    .doesNotContain("investor_trend", "short_sale_domestic", "credit_balance");
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
