package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("StockListCacheRepository 통합 테스트")
class StockListCacheRepositoryIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StockListCacheRepository repository;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        ObjectMapper objectMapper =
                new Jackson2ObjectMapperBuilder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .timeZone("Asia/Seoul")
                        .modulesToInstall(new JavaTimeModule())
                        .build();
        repository = new StockListCacheRepository(redisTemplate, objectMapper);
    }

    @AfterEach
    void tearDown() {
        repository.delete();
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("save 후 findAll — 저장한 목록 조회 성공")
    void saveAndFindAll_returnsStoredList() {
        // Arrange
        List<CachedStock> stocks =
                List.of(
                        new CachedStock(
                                "005930",
                                "삼성전자",
                                "Samsung Electronics",
                                Market.KOSPI,
                                AssetType.STOCK,
                                LocalDate.of(1975, 6, 11)),
                        new CachedStock(
                                "AAPL",
                                "애플",
                                "Apple Inc.",
                                Market.NASDAQ,
                                AssetType.STOCK,
                                LocalDate.of(1980, 12, 12)));

        // Act
        repository.save(stocks);
        Optional<List<CachedStock>> result = repository.findAll();

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().getFirst().symbol()).isEqualTo("005930");
        assertThat(result.get().get(1).market()).isEqualTo(Market.NASDAQ);
    }

    @Test
    @DisplayName("캐시 없을 때 findAll — Optional.empty() 반환")
    void findAll_whenNoCacheExists_returnsEmpty() {
        Optional<List<CachedStock>> result = repository.findAll();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("delete 후 findAll — Optional.empty() 반환")
    void deleteAndFindAll_returnsEmpty() {
        // Arrange
        List<CachedStock> stocks =
                List.of(
                        new CachedStock(
                                "005930",
                                "삼성전자",
                                "Samsung Electronics",
                                Market.KOSPI,
                                AssetType.STOCK,
                                LocalDate.of(1975, 6, 11)));
        repository.save(stocks);

        // Act
        repository.delete();
        Optional<List<CachedStock>> result = repository.findAll();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("save — TTL 미설정 확인 (키가 영구 유지됨)")
    void save_noTtl_keyPersistsWithoutExpiry() {
        // Arrange
        List<CachedStock> stocks =
                List.of(
                        new CachedStock(
                                "005930", "삼성전자", null, Market.KOSPI, AssetType.STOCK, null));

        // Act
        repository.save(stocks);

        // Assert — TTL이 없으면 getExpire 결과가 -1 (영구)
        Long ttl =
                connectionFactory
                        .getConnection()
                        .keyCommands()
                        .pTtl("cache:stock:list".getBytes(StandardCharsets.UTF_8));
        assertThat(ttl).isEqualTo(-1L);
    }

    @Test
    @DisplayName("save 덮어쓰기 — 기존 캐시 대체")
    void save_overwritesExistingCache() {
        // Arrange
        List<CachedStock> first =
                List.of(
                        new CachedStock(
                                "005930", "삼성전자", null, Market.KOSPI, AssetType.STOCK, null));
        List<CachedStock> second =
                List.of(
                        new CachedStock(
                                "AAPL", "애플", "Apple", Market.NASDAQ, AssetType.STOCK, null),
                        new CachedStock(
                                "MSFT",
                                "마이크로소프트",
                                "Microsoft",
                                Market.NASDAQ,
                                AssetType.STOCK,
                                null));

        // Act
        repository.save(first);
        repository.save(second);
        Optional<List<CachedStock>> result = repository.findAll();

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().getFirst().symbol()).isEqualTo("AAPL");
    }
}
