package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.ShortSaleOverseasRepository.ShortInterestSnapshot;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
@DisplayName("ShortSaleOverseasRepository 통합 테스트 (Tier-2 UPSERT + 포워드 조회)")
@Tag("integration")
class ShortSaleOverseasRepositoryIT {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    private static final AtomicInteger SYMBOL_SEQ = new AtomicInteger();

    @Autowired private ShortSaleOverseasRepository repository;
    @Autowired private StockRepository stockRepository;

    /** 테스트 메서드 간 {@code uk_stocks_symbol_market} 충돌을 피하려 매 호출 유니크 심볼을 생성한다. */
    private Stock savedUsStock() {
        String symbol = "US" + SYMBOL_SEQ.incrementAndGet();
        return stockRepository.save(
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목_" + symbol)
                        .market(Market.NASDAQ)
                        .assetType(AssetType.STOCK)
                        .listedDate(LocalDate.of(2015, 1, 1))
                        .build());
    }

    private List<ShortSaleOverseas> rowsOf(Long stockId) {
        return repository.findAll().stream()
                .filter(r -> r.getStock().getId().equals(stockId))
                .toList();
    }

    private ShortSaleOverseas findRow(Long stockId, LocalDate tradeDate) {
        return rowsOf(stockId).stream()
                .filter(r -> r.getTradeDate().equals(tradeDate))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("upsertDaily — 멱등 + 소스별 SET")
    class UpsertDaily {

        @Test
        @DisplayName(
                "동일 (stock_id, trade_date) 재수집은 행 수 불변, short_volume/total_volume 갱신 (AC-UPSERT-1,3)")
        void idempotentUpsertUpdatesVolumes() {
            // Arrange
            Stock aapl = savedUsStock();
            LocalDate tradeDate = LocalDate.of(2026, 1, 6);
            repository.upsertDaily(
                    aapl.getId(), tradeDate, 100L, 200L, LocalDateTime.now(), null, null);

            // Act: 같은 키 재수집 — 합산값 변경
            repository.upsertDaily(
                    aapl.getId(),
                    tradeDate,
                    5_159_846L,
                    18_442_863L,
                    LocalDateTime.now(),
                    null,
                    null);

            // Assert
            assertThat(rowsOf(aapl.getId())).hasSize(1);
            ShortSaleOverseas row = findRow(aapl.getId(), tradeDate);
            assertThat(row.getShortVolume()).isEqualTo(5_159_846L);
            assertThat(row.getTotalVolume()).isEqualTo(18_442_863L);
            assertThat(row.getDailyCollectedAt()).isNotNull();
        }

        @Test
        @DisplayName("forward 매칭값(short_interest)을 함께 적재한다 (AC-LOCF-1)")
        void upsertDailyWithForwardInterest() {
            // Arrange
            Stock aapl = savedUsStock();
            LocalDate tradeDate = LocalDate.of(2026, 1, 6);
            LocalDate siDate = LocalDate.of(2026, 1, 2);

            // Act
            repository.upsertDaily(
                    aapl.getId(), tradeDate, 100L, 200L, LocalDateTime.now(), 134_422_787L, siDate);

            // Assert
            ShortSaleOverseas row = findRow(aapl.getId(), tradeDate);
            assertThat(row.getShortInterest()).isEqualTo(134_422_787L);
            assertThat(row.getShortInterestDate()).isEqualTo(siDate);
            assertThat(row.getInterestCollectedAt()).isNull();
        }

        @Test
        @DisplayName(
                "forward 매칭 없으면(null) Daily 재수집이 기존 short_interest를 보존한다 (AC-UPSERT-1, AC-LOCF-2)")
        void daatilyReupsertPreservesExistingInterest() {
            // Arrange: interest가 채워진 행
            Stock aapl = savedUsStock();
            LocalDate tradeDate = LocalDate.of(2026, 1, 6);
            repository.upsertDaily(
                    aapl.getId(),
                    tradeDate,
                    100L,
                    200L,
                    LocalDateTime.now(),
                    134_422_787L,
                    LocalDate.of(2026, 1, 2));

            // Act: forward 매칭 없는(null) Daily 재수집
            repository.upsertDaily(
                    aapl.getId(), tradeDate, 999L, 1000L, LocalDateTime.now(), null, null);

            // Assert: volume은 갱신, interest는 보존
            ShortSaleOverseas row = findRow(aapl.getId(), tradeDate);
            assertThat(row.getShortVolume()).isEqualTo(999L);
            assertThat(row.getShortInterest()).isEqualTo(134_422_787L);
        }
    }

    @Nested
    @DisplayName("upsertInterest — interest 계열만 SET, Daily 컬럼 보존")
    class UpsertInterest {

        @Test
        @DisplayName("SI-origin 행은 daily_collected_at NULL 유지 (AC-PHANTOM-1, REQ-SSO-007)")
        void siOriginRowKeepsDailyCollectedAtNull() {
            // Arrange
            Stock aapl = savedUsStock();
            LocalDate settlementDate = LocalDate.of(2026, 4, 15);

            // Act
            repository.upsertInterest(
                    aapl.getId(), settlementDate, 134_422_787L, LocalDateTime.now());

            // Assert
            ShortSaleOverseas row = findRow(aapl.getId(), settlementDate);
            // V7 NOT NULL DEFAULT 0이라 short_volume은 0이지만 daily_collected_at은 NULL → 미수집 구분
            assertThat(row)
                    .satisfies(
                            r -> {
                                assertThat(r.getShortInterest()).isEqualTo(134_422_787L);
                                assertThat(r.getShortInterestDate()).isEqualTo(settlementDate);
                                assertThat(r.getInterestCollectedAt()).isNotNull();
                                assertThat(r.getShortVolume()).isEqualTo(0L);
                                assertThat(r.getDailyCollectedAt()).isNull();
                            })
                    .satisfies(
                            r -> {
                                assertThat(r.getFloatShares()).isNull();
                                assertThat(r.getSiPctFloat()).isNull();
                            });
        }

        @Test
        @DisplayName("같은 날짜 Daily 컬럼을 보존한다 (AC-UPSERT-2, REQ-SSO-022)")
        void preservesDailyColumns() {
            // Arrange: Daily 먼저 적재된 행
            Stock aapl = savedUsStock();
            LocalDate date = LocalDate.of(2026, 1, 15);
            LocalDateTime dailyAt = LocalDateTime.of(2026, 1, 16, 10, 0);
            repository.upsertDaily(aapl.getId(), date, 5000L, 9000L, dailyAt, null, null);

            // Act: 같은 날짜에 SI 적재 (settlementDate == trade_date)
            repository.upsertInterest(aapl.getId(), date, 134_422_787L, LocalDateTime.now());

            // Assert: Daily 컬럼 보존, interest 컬럼만 갱신
            ShortSaleOverseas row = findRow(aapl.getId(), date);
            assertThat(row.getShortVolume()).isEqualTo(5000L);
            assertThat(row.getTotalVolume()).isEqualTo(9000L);
            assertThat(row.getDailyCollectedAt()).isEqualTo(dailyAt);
            assertThat(row.getShortInterest()).isEqualTo(134_422_787L);
        }

        @Test
        @DisplayName("revisionFlag=\"R\" 재적재가 interest 컬럼을 갱신하고 Daily 컬럼은 불변 (REQ-SSO-014b)")
        void revisionUpdatesInterestKeepsDaily() {
            // Arrange: Daily + SI가 모두 채워진 settlementDate 행
            Stock aapl = savedUsStock();
            LocalDate date = LocalDate.of(2026, 4, 15);
            LocalDateTime dailyAt = LocalDateTime.of(2026, 4, 16, 10, 0);
            repository.upsertDaily(aapl.getId(), date, 7_000L, 12_000L, dailyAt, null, null);
            repository.upsertInterest(aapl.getId(), date, 126_771_284L, LocalDateTime.now());

            // Act: revision 갱신값 UPSERT (같은 settlementDate, 수정 잔고)
            repository.upsertInterest(aapl.getId(), date, 140_000_000L, LocalDateTime.now());

            // Assert
            ShortSaleOverseas row = findRow(aapl.getId(), date);
            assertThat(row.getShortInterest()).isEqualTo(140_000_000L);
            assertThat(row.getShortVolume()).isEqualTo(7_000L);
            assertThat(row.getTotalVolume()).isEqualTo(12_000L);
            assertThat(row.getDailyCollectedAt()).isEqualTo(dailyAt);
        }
    }

    @Nested
    @DisplayName("findLatestShortInterestByStockIds — 배치 forward 조회 (N+1 회피)")
    class FindLatestShortInterest {

        @Test
        @DisplayName("종목별 short_interest_date <= tradeDate인 최신 1건을 Map으로 반환 (AC-LOCF-1, D5)")
        void returnsLatestPerStock() {
            // Arrange
            Stock aapl = savedUsStock();
            Stock msft = savedUsStock();
            // AAPL: 두 settlementDate 행 (최신 = 2026-01-02)
            repository.upsertInterest(
                    aapl.getId(), LocalDate.of(2025, 12, 15), 100L, LocalDateTime.now());
            repository.upsertInterest(
                    aapl.getId(), LocalDate.of(2026, 1, 2), 200L, LocalDateTime.now());
            // MSFT: 하나
            repository.upsertInterest(
                    msft.getId(), LocalDate.of(2025, 12, 31), 300L, LocalDateTime.now());

            // Act: tradeDate=2026-01-06 기준 forward 조회
            Map<Long, ShortInterestSnapshot> result =
                    repository.findLatestShortInterestByStockIds(
                            Set.of(aapl.getId(), msft.getId()), LocalDate.of(2026, 1, 6));

            // Assert: AAPL은 최신 2026-01-02(200), MSFT는 2025-12-31(300)
            assertThat(result).hasSize(2);
            assertThat(result.get(aapl.getId()).shortInterest()).isEqualTo(200L);
            assertThat(result.get(aapl.getId()).shortInterestDate())
                    .isEqualTo(LocalDate.of(2026, 1, 2));
            assertThat(result.get(msft.getId()).shortInterest()).isEqualTo(300L);
        }

        @Test
        @DisplayName("short_interest_date > tradeDate인 미래 잔고는 제외한다")
        void excludesFutureInterest() {
            // Arrange
            Stock aapl = savedUsStock();
            repository.upsertInterest(
                    aapl.getId(), LocalDate.of(2026, 1, 31), 500L, LocalDateTime.now());

            // Act: tradeDate가 잔고 날짜보다 이전
            Map<Long, ShortInterestSnapshot> result =
                    repository.findLatestShortInterestByStockIds(
                            Set.of(aapl.getId()), LocalDate.of(2026, 1, 6));

            // Assert
            assertThat(result).doesNotContainKey(aapl.getId());
        }

        @Test
        @DisplayName("short_interest IS NULL인 Daily 전용 행은 forward 출처가 되지 않는다")
        void excludesDailyOnlyRows() {
            // Arrange: Daily만 적재(short_interest NULL)
            Stock aapl = savedUsStock();
            repository.upsertDaily(
                    aapl.getId(),
                    LocalDate.of(2026, 1, 2),
                    100L,
                    200L,
                    LocalDateTime.now(),
                    null,
                    null);

            // Act
            Map<Long, ShortInterestSnapshot> result =
                    repository.findLatestShortInterestByStockIds(
                            Set.of(aapl.getId()), LocalDate.of(2026, 1, 6));

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findExistingInterestPairsByStockIds — 종목×날짜 쌍 단위 미적재 판정용 (REQ-SSO-014a)")
    class FindExistingInterestPairs {

        @Test
        @DisplayName("적재된 (stock_id, short_interest_date) 쌍을 종목별 Set으로 반환한다")
        void returnsPairsPerStock() {
            // Arrange
            Stock aapl = savedUsStock();
            Stock msft = savedUsStock();
            repository.upsertInterest(
                    aapl.getId(), LocalDate.of(2026, 4, 15), 100L, LocalDateTime.now());
            repository.upsertInterest(
                    aapl.getId(), LocalDate.of(2026, 4, 30), 200L, LocalDateTime.now());
            repository.upsertInterest(
                    msft.getId(), LocalDate.of(2026, 4, 15), 300L, LocalDateTime.now());

            // Act
            Map<Long, Set<LocalDate>> pairs =
                    repository.findExistingInterestPairsByStockIds(
                            Set.of(aapl.getId(), msft.getId()),
                            LocalDate.of(2026, 4, 1),
                            LocalDate.of(2026, 6, 19));

            // Assert: 종목별 날짜 집합이 독립적으로 반환된다
            assertThat(pairs.get(aapl.getId()))
                    .containsExactlyInAnyOrder(
                            LocalDate.of(2026, 4, 15), LocalDate.of(2026, 4, 30));
            assertThat(pairs.get(msft.getId()))
                    .containsExactlyInAnyOrder(LocalDate.of(2026, 4, 15));
        }

        @Test
        @DisplayName("종목 집합이 비어 있으면 빈 Map을 반환한다 (IN 빈 절 회피)")
        void returnsEmptyMapForEmptyStockIds() {
            Map<Long, Set<LocalDate>> pairs =
                    repository.findExistingInterestPairsByStockIds(
                            Set.of(), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 19));

            assertThat(pairs).isEmpty();
        }
    }
}
