package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockListService 단위 테스트")
class StockListServiceTest {

    @Mock private StockListCacheRepository cacheRepository;
    @Mock private StockRepository stockRepository;

    private StockListService service;

    @BeforeEach
    void setUp() {
        service = new StockListService(cacheRepository, stockRepository);
    }

    @Nested
    @DisplayName("findActiveStocks — 캐시 우선 조회")
    class FindActiveStocks {

        @Test
        @DisplayName("캐시 히트 — DB 조회 없이 캐시 목록 반환")
        void findActiveStocks_whenCacheHit_returnsCacheWithoutDbQuery() {
            // Arrange
            List<CachedStock> cached =
                    List.of(
                            new CachedStock(
                                    "005930",
                                    "삼성전자",
                                    "Samsung Electronics",
                                    Market.KOSPI,
                                    AssetType.STOCK,
                                    LocalDate.of(1975, 6, 11)));
            when(cacheRepository.findAll()).thenReturn(Optional.of(cached));

            // Act
            List<CachedStock> result = service.findActiveStocks();

            // Assert
            assertThat(result).isEqualTo(cached);
            verify(stockRepository, never()).findAllActive();
        }

        @Test
        @DisplayName("캐시 미스 — DB 조회 후 캐시 워밍업 및 목록 반환")
        void findActiveStocks_whenCacheMiss_queriesDbAndWarmsUpCache() {
            // Arrange
            when(cacheRepository.findAll()).thenReturn(Optional.empty());
            Stock dbStock =
                    Stock.builder()
                            .symbol("005930")
                            .nameKo("삼성전자")
                            .nameEn("Samsung Electronics")
                            .market(Market.KOSPI)
                            .assetType(AssetType.STOCK)
                            .listedDate(LocalDate.of(1975, 6, 11))
                            .active(true)
                            .build();
            when(stockRepository.findAllActive()).thenReturn(List.of(dbStock));

            // Act
            List<CachedStock> result = service.findActiveStocks();

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().symbol()).isEqualTo("005930");
            verify(cacheRepository).save(anyList());
        }

        @Test
        @DisplayName("캐시 미스 + DB 빈 목록 — 빈 목록 반환, 캐시 워밍업 미호출")
        void findActiveStocks_whenCacheMissAndDbEmpty_returnsEmptyAndSavesCache() {
            // Arrange
            when(cacheRepository.findAll()).thenReturn(Optional.empty());
            when(stockRepository.findAllActive()).thenReturn(List.of());

            // Act
            List<CachedStock> result = service.findActiveStocks();

            // Assert
            assertThat(result).isEmpty();
            verify(cacheRepository, never()).save(any());
        }
    }
}
