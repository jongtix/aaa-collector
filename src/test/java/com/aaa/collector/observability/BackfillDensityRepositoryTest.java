package com.aaa.collector.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillDensityRepository — 밀도 게이지 A/B 종목 수 Redis 저장/조회 (REQ-WSR4-002)")
class BackfillDensityRepositoryTest {

    private static final String BELOW_FLOOR_PREFIX =
            "observability:collector:backfill-below-floor:";
    private static final String INTERNAL_GAP_PREFIX =
            "observability:collector:backfill-internal-gap:";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private BackfillDensityRepository repository() {
        return new BackfillDensityRepository(redisTemplate);
    }

    private void stubOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("게이지 A — below-floor")
    class BelowFloor {

        @Test
        @DisplayName("saveBelowFloor — TTL 없이 below-floor 키로 종목 수 저장")
        void save_callsSetWithoutTtl() {
            stubOpsForValue();

            repository().saveBelowFloor("daily-ohlcv-krx", 3L);

            verify(valueOps).set(BELOW_FLOOR_PREFIX + "daily-ohlcv-krx", "3");
        }

        @Test
        @DisplayName("findBelowFloor — 부재 키는 Optional.empty() 반환")
        void find_whenAbsent_returnsEmpty() {
            stubOpsForValue();
            when(valueOps.get(BELOW_FLOOR_PREFIX + "daily-ohlcv-krx")).thenReturn(null);

            assertThat(repository().findBelowFloor("daily-ohlcv-krx")).isEmpty();
        }

        @Test
        @DisplayName("findBelowFloor — 존재하는 값은 long으로 환원해 반환")
        void find_whenPresent_returnsLong() {
            stubOpsForValue();
            when(valueOps.get(BELOW_FLOOR_PREFIX + "daily-ohlcv-us")).thenReturn("7");

            assertThat(repository().findBelowFloor("daily-ohlcv-us")).contains(7L);
        }

        @Test
        @DisplayName("findBelowFloor — 비숫자(손상) 값이면 예외 없이 Optional.empty() 반환")
        void find_whenCorrupted_returnsEmpty() {
            stubOpsForValue();
            when(valueOps.get(BELOW_FLOOR_PREFIX + "daily-ohlcv-krx")).thenReturn("nan");

            assertThat(repository().findBelowFloor("daily-ohlcv-krx")).isEmpty();
        }
    }

    @Nested
    @DisplayName("게이지 B — internal-gap")
    class InternalGap {

        @Test
        @DisplayName("saveInternalGap — TTL 없이 internal-gap 키로 종목 수 저장")
        void save_callsSetWithoutTtl() {
            stubOpsForValue();

            repository().saveInternalGap("daily-ohlcv-us", 5L);

            verify(valueOps).set(INTERNAL_GAP_PREFIX + "daily-ohlcv-us", "5");
        }

        @Test
        @DisplayName("findInternalGap — 존재하는 값은 long으로 환원해 반환")
        void find_whenPresent_returnsLong() {
            stubOpsForValue();
            when(valueOps.get(INTERNAL_GAP_PREFIX + "daily-ohlcv-krx")).thenReturn("12");

            assertThat(repository().findInternalGap("daily-ohlcv-krx")).contains(12L);
        }

        @Test
        @DisplayName("findInternalGap — 부재 키는 Optional.empty() 반환")
        void find_whenAbsent_returnsEmpty() {
            stubOpsForValue();
            when(valueOps.get(INTERNAL_GAP_PREFIX + "daily-ohlcv-us")).thenReturn(null);

            assertThat(repository().findInternalGap("daily-ohlcv-us")).isEmpty();
        }
    }

    @Test
    @DisplayName("below-floor/internal-gap 키가 서로 다른 접두어로 격리된다")
    void belowFloorAndInternalGap_useDistinctKeys() {
        stubOpsForValue();
        BackfillDensityRepository repository = repository();

        repository.saveBelowFloor("daily-ohlcv-krx", 1L);
        repository.saveInternalGap("daily-ohlcv-krx", 2L);

        verify(valueOps).set(BELOW_FLOOR_PREFIX + "daily-ohlcv-krx", "1");
        verify(valueOps).set(INTERNAL_GAP_PREFIX + "daily-ohlcv-krx", "2");
    }
}
