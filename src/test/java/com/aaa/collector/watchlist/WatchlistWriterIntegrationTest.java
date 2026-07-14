package com.aaa.collector.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.stock.Stock;
import com.aaa.collector.stock.StockListService;
import com.aaa.collector.stock.StockRepository;
import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.aaa.collector.support.SharedMySqlContainer;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
@DisplayName("WatchlistWriter 통합 테스트 — JPA dirty-check DB 반영 검증")
@Tag("integration")
class WatchlistWriterIntegrationTest {

    @ServiceConnection // @Container 미부착 — 싱글턴 컨테이너 패턴(SharedMySqlContainer 참조). 생명주기는
    // SharedMySqlContainer의 static 블록이 소유하며, 각 클래스가 @Container로 재선언하면 클래스 종료 시
    // 공유 컨테이너가 죽는다.
    static final MySQLContainer<?> MYSQL = SharedMySqlContainer.MYSQL;

    @MockitoBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    @MockitoBean private BatchLastLoadRepository batchLastLoadRepository;
    @MockitoBean private MarketIndicatorLastSuccessRepository marketIndicatorLastSuccessRepository;
    @MockitoBean private StockListService stockListService;

    @Autowired private WatchlistWriter watchlistWriter;
    @Autowired private StockRepository stockRepository;
    @Autowired private EntityManager em;

    private Stock savedInactiveStock(String symbol, Market market) {
        Stock stock =
                Stock.builder()
                        .symbol(symbol)
                        .nameKo("테스트종목")
                        .market(market)
                        .assetType(AssetType.STOCK)
                        .active(false)
                        .build();
        stockRepository.save(stock);
        em.flush();
        em.clear();
        return stock;
    }

    @Nested
    @DisplayName("dirty-check — 엔티티 변경이 DB에 실제 반영됨")
    class DirtyCheck {

        @Test
        @DisplayName("비활성 종목 — upsertAll 후 DB 레코드 active=true")
        void upsertAll_inactiveStock_activatedInDb() {
            Stock saved = savedInactiveStock("005930", Market.KOSPI);

            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "테스트종목", Market.KOSPI, null)), 0);
            em.flush();
            em.clear();

            Stock result = stockRepository.findById(saved.getId()).orElseThrow();
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("watchlistRemovedAt 설정된 종목 — upsertAll 후 DB 레코드 watchlistRemovedAt=null")
        void upsertAll_stockWithWatchlistRemovedAt_clearedInDb() {
            Stock stock =
                    Stock.builder()
                            .symbol("000660")
                            .nameKo("SK하이닉스")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .active(true)
                            .watchlistRemovedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                            .build();
            stockRepository.save(stock);
            em.flush();
            em.clear();

            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("000660", "SK하이닉스", Market.KOSPI, null)), 0);
            em.flush();
            em.clear();

            Stock result = stockRepository.findById(stock.getId()).orElseThrow();
            assertThat(result.getWatchlistRemovedAt()).isNull();
        }

        @Test
        @DisplayName(
                "동일 symbol 다른 market 종목 — markWatchlistRemoved 후 DB 레코드 watchlistRemovedAt 설정됨")
        void upsertAll_sameSymbolDifferentMarket_watchlistRemovedAtSetInDb() {
            // Arrange — DB에 ACME:NYSE 존재, 이번 sync에는 ACME:NASDAQ만 등장
            Stock nyse =
                    stockRepository.save(
                            Stock.builder()
                                    .symbol("ACME")
                                    .nameKo("ACME Inc")
                                    .market(Market.NYSE)
                                    .assetType(AssetType.STOCK)
                                    .active(true)
                                    .build());
            em.flush();
            em.clear();

            // Act
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("ACME", "ACME Inc", Market.NASDAQ, null)), 0);
            em.flush();
            em.clear();

            // Assert
            Stock result = stockRepository.findById(nyse.getId()).orElseThrow();
            assertThat(result.getWatchlistRemovedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("캐시 갱신 — failedGroupCount 조건 검증")
    class CacheUpdate {

        @Test
        @DisplayName("failedGroupCount=0 — stockListService.refreshCache() 1회 호출")
        void upsertAll_noGroupFailed_cacheIsSaved() {
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 0);
            em.flush();
            em.clear();

            verify(stockListService, times(1)).refreshCache();
        }

        @Test
        @DisplayName("failedGroupCount>0 — stockListService.refreshCache() 미호출")
        void upsertAll_groupFailed_cacheIsNotSaved() {
            watchlistWriter.upsertAll(
                    List.of(new ResolvedStock("005930", "삼성전자", Market.KOSPI, null)), 1);
            em.flush();
            em.clear();

            verify(stockListService, never()).refreshCache();
        }
    }
}
