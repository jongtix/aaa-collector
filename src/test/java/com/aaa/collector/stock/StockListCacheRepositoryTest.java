package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.stock.enums.AssetType;
import com.aaa.collector.stock.enums.Market;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockListCacheRepository 단위 테스트")
class StockListCacheRepositoryTest {

    private static final String CACHE_KEY = "cache:stock:list";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private StockListCacheRepository repository;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repository = new StockListCacheRepository(redisTemplate, objectMapper);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("save — 직렬화 후 Redis에 저장")
    class Save {

        @Test
        @DisplayName("정상 목록 — TTL 없이 opsForValue().set(key, json) 호출")
        void save_normalList_callsSetWithoutTtl() {
            stubOpsForValue();
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

            verify(valueOps).set(eq(CACHE_KEY), any(String.class));
        }

        @Test
        @DisplayName("Redis 오류 — warn 로그만 남기고 예외 전파 안 함 (캐시 저장 실패)")
        void save_redisError_doesNotPropagate() {
            // Arrange
            stubOpsForValue();
            doThrow(new RuntimeException("set failed")).when(valueOps).set(any(), any());
            List<CachedStock> stocks =
                    List.of(
                            new CachedStock(
                                    "005930", "삼성전자", null, Market.KOSPI, AssetType.STOCK, null));

            // Act & Assert
            assertThatNoException().isThrownBy(() -> repository.save(stocks));
        }

        @Test
        @DisplayName("JSON 직렬화 예외 — warn 로그만 남기고 예외 전파 안 함, Redis 쓰기 미호출")
        void save_jsonSerializationException_doesNotPropagate() throws Exception {
            // Arrange
            ObjectMapper mockMapper = mock(ObjectMapper.class);
            when(mockMapper.writeValueAsString(any()))
                    .thenThrow(new JsonProcessingException("test") {});
            StockListCacheRepository repo = new StockListCacheRepository(redisTemplate, mockMapper);

            // Act & Assert
            assertThatNoException().isThrownBy(() -> repo.save(List.of()));
            verify(redisTemplate, never()).opsForValue();
        }
    }

    @Nested
    @DisplayName("findAll — Redis 조회 및 역직렬화")
    class FindAll {

        @Test
        @DisplayName("캐시 존재 — Optional<List<CachedStock>> 반환")
        void findAll_whenCacheExists_returnsDeserializedList() throws Exception {
            // Arrange
            stubOpsForValue();
            CachedStock expected =
                    new CachedStock(
                            "005930",
                            "삼성전자",
                            "Samsung Electronics",
                            Market.KOSPI,
                            AssetType.STOCK,
                            LocalDate.of(1975, 6, 11));
            String json =
                    new ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .writeValueAsString(List.of(expected));
            when(valueOps.get(CACHE_KEY)).thenReturn(json);

            // Act
            Optional<List<CachedStock>> result = repository.findAll();

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(1);
            assertThat(result.get().get(0).symbol()).isEqualTo("005930");
        }

        @Test
        @DisplayName("캐시 미스 — Optional.empty() 반환")
        void findAll_whenCacheMiss_returnsEmpty() {
            stubOpsForValue();
            when(valueOps.get(CACHE_KEY)).thenReturn(null);

            Optional<List<CachedStock>> result = repository.findAll();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("역직렬화 예외 — Optional.empty() 반환, 예외 전파 안 함")
        void findAll_deserializationException_returnsEmptyAndDoesNotPropagate() {
            stubOpsForValue();
            when(valueOps.get(CACHE_KEY)).thenReturn("invalid-json{{{}");

            Optional<List<CachedStock>> result = repository.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("delete — 캐시 키 삭제")
    class Delete {

        @Test
        @DisplayName("delete — redisTemplate.delete(key) 호출")
        void delete_callsDeleteWithKey() {
            repository.delete();

            verify(redisTemplate).delete(CACHE_KEY);
        }
    }
}
