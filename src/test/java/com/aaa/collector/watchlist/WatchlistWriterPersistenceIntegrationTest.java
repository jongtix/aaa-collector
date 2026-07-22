package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.CoverageRatioRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListService;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.ListingStatus;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.RootFixtureCleaner;
import com.aaa.collector.support.SharedMySqlContainer;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

/**
 * WatchlistWriter 비트랜잭션 영속성 통합 테스트 (SPEC-COLLECTOR-WLSYNC-009 M1).
 *
 * <p>클래스 레벨 {@code @Transactional}을 의도적으로 두지 않는다 — 이 테스트의 목적 자체가 프로덕션과 동일한 비트랜잭션 배치 스레드 조건에서 {@code
 * WatchlistWriter.upsertAll}이 실제 COMMIT을 일으키는지 검증하는 것이다. 테스트 트랜잭션이 감싸면 {@code loadExisting}이 로드한
 * 엔티티가 테스트 트랜잭션에 합류해 managed 상태가 되어 위양성이 재현된다({@code WatchlistWriterIntegrationTest}가 그 위양성 사례였다 —
 * 본 클래스가 대체한다).
 *
 * <p>각 검증은 {@code upsertAll} 호출이 반환된 뒤(직전 트랜잭션이 이미 커밋·종료된 시점) {@code stockRepository.findById}로
 * 재조회한다 — 재조회 자체가 새 트랜잭션·새 영속성 컨텍스트를 여는 JPA 리포지토리 호출이므로 1차 캐시 위양성 없이 fresh한 DB row를 읽는다.
 */
@SpringBootTest
@ActiveProfiles({"test", "db-integration"})
@Testcontainers
@DisplayName("WatchlistWriter 영속성 통합 테스트 — 비트랜잭션 실제 COMMIT 검증 (SPEC-COLLECTOR-WLSYNC-009)")
@Tag("integration")
class WatchlistWriterPersistenceIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private CoverageRatioRepository coverageRatioRepository;
    @MockitoBean private BackfillDensityRepository backfillDensityRepository;
    @MockitoBean private StockListService stockListService;

    @Autowired private WatchlistWriter watchlistWriter;
    @Autowired private StockRepository stockRepository;
    @Autowired private EntityManager em;

    private final List<Long> createdStockIds = new ArrayList<>();

    @AfterEach
    void cleanUpResidualRows() {
        // collector 앱 계정에는 DELETE 권한이 없다(ADR-026) — root 커넥션으로만 정리한다. 이 테스트가
        // 생성한 symbol 범위만 스코프 한정 삭제하며 stocks 테이블 전체를 비우지 않는다.
        JdbcTemplate rootJdbcTemplate = RootFixtureCleaner.rootJdbcTemplate(MYSQL.getJdbcUrl());
        for (Long stockId : createdStockIds) {
            rootJdbcTemplate.update("DELETE FROM stocks WHERE id = ?", stockId);
        }
        createdStockIds.clear();
    }

    private Stock savedStock(
            String symbol,
            Market market,
            String nameKo,
            boolean active,
            LocalDateTime watchlistRemovedAt) {
        Stock stock =
                stockRepository.save(
                        Stock.builder()
                                .symbol(symbol)
                                .nameKo(nameKo)
                                .market(market)
                                .assetType(AssetType.STOCK)
                                .active(active)
                                .watchlistRemovedAt(watchlistRemovedAt)
                                .build());
        createdStockIds.add(stock.getId());
        return stock;
    }

    @Nested
    @DisplayName("UPDATE 경로 — 커밋 후 fresh 재조회로 반영 검증 (REQ-WLSYNC-155, 156, 158)")
    class UpdatePersistence {

        @Test
        @DisplayName("이름 변경 — 커밋 후 fresh 재조회에서 새 이름 반영 (시나리오 1)")
        void upsertAll_nameChanged_persistedAfterCommit() {
            // Arrange
            Stock stock = savedStock("005930", Market.KOSPI, "옛이름", true, null);

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "새이름", Market.KOSPI, null)), 0);
            em.clear();

            // Assert
            Stock result = stockRepository.findById(stock.getId()).orElseThrow();
            assertThat(result.getNameKo()).isEqualTo("새이름");
        }

        @Test
        @DisplayName("watchlistRemovedAt 리셋 — 커밋 후 fresh 재조회에서 null (시나리오 2)")
        void upsertAll_watchlistRemovedAtReset_persistedAfterCommit() {
            // Arrange
            Stock stock =
                    savedStock(
                            "000660",
                            Market.KOSPI,
                            "SK하이닉스",
                            true,
                            LocalDateTime.of(2026, 1, 1, 0, 0));

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null)), 0);
            em.clear();

            // Assert
            Stock result = stockRepository.findById(stock.getId()).orElseThrow();
            assertThat(result.getWatchlistRemovedAt()).isNull();
        }

        @Test
        @DisplayName("상폐 격리 — 커밋 후 fresh 재조회에서 active=false + delistedAt 설정 (시나리오 3, #39 회귀)")
        void upsertAll_delistingDetected_persistedAfterCommit() {
            // Arrange
            Stock stock = savedStock("010620", Market.KOSPI, "HD현대미포", true, null);
            StockInfo info =
                    new StockInfo(
                            AssetType.STOCK,
                            "HD Hyundai Mipo",
                            null,
                            null,
                            Market.KOSPI,
                            ListingStatus.DELISTED,
                            LocalDate.of(2025, 12, 15));

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("010620", "HD현대미포", Market.KOSPI, info)), 0);
            em.clear();

            // Assert
            Stock result = stockRepository.findById(stock.getId()).orElseThrow();
            assertThat(result.isActive()).isFalse();
            assertThat(result.getDelistedAt()).isEqualTo(LocalDate.of(2025, 12, 15));
        }

        @Test
        @DisplayName("동일 값 재감지 — 2차 sync에도 DB row 안정, unchanged로 수렴 (시나리오 5)")
        void upsertAll_sameValueTwice_convergesToStableRow() {
            // Arrange
            Stock stock = savedStock("035420", Market.KOSPI, "NAVER", true, null);

            // Act — 1차 sync가 이름을 변경, 2차 sync는 동일 값 재관찰
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("035420", "네이버", Market.KOSPI, null)), 0);
            em.clear();
            Stock afterFirst = stockRepository.findById(stock.getId()).orElseThrow();

            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("035420", "네이버", Market.KOSPI, null)), 0);
            em.clear();
            Stock afterSecond = stockRepository.findById(stock.getId()).orElseThrow();

            // Assert — 현재 DB row가 기준선이므로 2차 sync에서 동일 변경이 재감지되지 않는다
            assertThat(afterFirst.getNameKo()).isEqualTo("네이버");
            assertThat(afterSecond.getNameKo()).isEqualTo("네이버");
        }
    }

    @Nested
    @DisplayName(
            "회귀 — 기존 INSERT 경로·동일 symbol 다른 market 제거 (시나리오 11, WatchlistWriterIntegrationTest 이관)")
    class RegressionPaths {

        @Test
        @DisplayName("신규 종목 INSERT — DB row 생성 확인")
        void upsertAll_newStock_insertedAndPersisted() {
            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("373220", "LG에너지솔루션", Market.KOSPI, null)), 0);

            // Assert
            Stock result =
                    stockRepository.findBySymbolAndMarket("373220", Market.KOSPI).orElseThrow();
            createdStockIds.add(result.getId());
            assertThat(result.getNameKo()).isEqualTo("LG에너지솔루션");
        }

        @Test
        @DisplayName("동일 symbol 다른 market 종목 — markWatchlistRemoved 커밋 후 fresh 재조회 반영")
        void upsertAll_sameSymbolDifferentMarket_watchlistRemovedAtPersisted() {
            // Arrange — DB에 ACME:NYSE 존재, 이번 sync에는 ACME:NASDAQ만 등장
            Stock nyse = savedStock("ACME", Market.NYSE, "ACME Inc", true, null);

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("ACME", "ACME Inc", Market.NASDAQ, null)), 0);
            em.clear();

            // Assert
            Stock result = stockRepository.findById(nyse.getId()).orElseThrow();
            assertThat(result.getWatchlistRemovedAt()).isNotNull();
            stockRepository
                    .findBySymbolAndMarket("ACME", Market.NASDAQ)
                    .ifPresent(s -> createdStockIds.add(s.getId()));
        }
    }

    @Nested
    @DisplayName("캐시 갱신 — failedGroupCount 조건 검증 (WatchlistWriterIntegrationTest 이관)")
    class CacheUpdate {

        @Test
        @DisplayName("failedGroupCount=0 — stockListService.refreshCache() 1회 호출")
        void upsertAll_noGroupFailed_cacheIsSaved() {
            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 0);
            stockRepository
                    .findBySymbolAndMarket("005930", Market.KOSPI)
                    .ifPresent(s -> createdStockIds.add(s.getId()));

            // Assert
            verify(stockListService, times(1)).refreshCache();
        }

        @Test
        @DisplayName("failedGroupCount>0 — stockListService.refreshCache() 미호출")
        void upsertAll_groupFailed_cacheIsNotSaved() {
            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 1);
            stockRepository
                    .findBySymbolAndMarket("005930", Market.KOSPI)
                    .ifPresent(s -> createdStockIds.add(s.getId()));

            // Assert
            verify(stockListService, never()).refreshCache();
        }
    }
}
