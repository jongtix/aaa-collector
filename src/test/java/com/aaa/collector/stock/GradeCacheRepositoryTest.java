package com.aaa.collector.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
@DisplayName("GradeCacheRepository 단위 테스트")
class GradeCacheRepositoryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private GradeCacheRepository repository;

    @BeforeEach
    void setUp() {
        repository = new GradeCacheRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("set — cache:grade:{symbol}에 등급 저장")
    class SetTests {

        @Test
        @DisplayName("정상 저장 — cache:grade:{symbol} 키로 등급 저장")
        void set_normalGrade_storedWithCorrectKey() {
            stubOpsForValue();

            repository.set("E1", "C");

            verify(valueOps).set(eq("cache:grade:E1"), eq("C"));
        }

        @Test
        @DisplayName("Redis 오류 — warn 로그만 남기고 예외 전파 안 함 (REQ-ETFCACHE-002)")
        void set_redisError_doesNotPropagate() {
            stubOpsForValue();
            doThrow(new RuntimeException("Redis connection failed"))
                    .when(valueOps)
                    .set(any(), any());

            assertThatNoException().isThrownBy(() -> repository.set("E1", "A"));
        }

        @Test
        @DisplayName("Redis 오류 시 DB 트랜잭션 영향 없음 (예외 비전파 검증)")
        void set_redisDown_transactionNotAffected() {
            // Redis being down should not propagate any exception
            doThrow(new RuntimeException("connection refused")).when(redisTemplate).opsForValue();

            assertThatNoException().isThrownBy(() -> repository.set("E2", "C"));
        }
    }

    @Nested
    @DisplayName("get — cache:grade:{symbol} 조회")
    class GetTests {

        @Test
        @DisplayName("캐시 존재 — 등급 반환")
        void get_cacheHit_returnsGrade() {
            stubOpsForValue();
            when(valueOps.get("cache:grade:E1")).thenReturn("A");

            String result = repository.get("E1");

            assertThat(result).isEqualTo("A");
        }

        @Test
        @DisplayName("캐시 미스 — null 반환")
        void get_cacheMiss_returnsNull() {
            stubOpsForValue();
            when(valueOps.get(anyString())).thenReturn(null);

            String result = repository.get("E1");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Redis 오류 — null 반환, 예외 전파 안 함")
        void get_redisError_returnsNullWithoutException() {
            doThrow(new RuntimeException("Redis down")).when(redisTemplate).opsForValue();

            assertThatNoException()
                    .isThrownBy(
                            () -> {
                                String result = repository.get("E1");
                                assertThat(result).isNull();
                            });
        }
    }
}
