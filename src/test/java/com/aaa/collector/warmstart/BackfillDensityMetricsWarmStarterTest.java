package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BackfillDensityMetrics;
import com.aaa.collector.observability.BackfillDensityRepository;
import com.aaa.collector.observability.WatermarkSeries;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * {@link BackfillDensityMetricsWarmStarter} — Redis 단일 소스 warm-start
 * (SPEC-COLLECTOR-WARMSTART-REDIS-004).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
        "BackfillDensityMetricsWarmStarter — Redis 단일 소스 below_floor/internal_gap gauge 초기화"
                + " (SPEC-COLLECTOR-WARMSTART-REDIS-004)")
class BackfillDensityMetricsWarmStarterTest {

    @Mock private BackfillDensityMetrics backfillDensityMetrics;
    @Mock private BackfillDensityRepository backfillDensityRepository;

    private BackfillDensityMetricsWarmStarter warmStarter() {
        return new BackfillDensityMetricsWarmStarter(
                backfillDensityMetrics, backfillDensityRepository);
    }

    @Nested
    @DisplayName("Redis 값 존재 — 게이지 A/B seed (REQ-WSR4-002)")
    class RedisValuePresent {

        @Test
        @DisplayName("KRX below_floor Redis 값이 있으면 warmBelowFloorCount 호출")
        void seedsBelowFloorFromRedis() {
            lenient()
                    .when(backfillDensityRepository.findBelowFloor("daily-ohlcv-krx"))
                    .thenReturn(Optional.of(3L));

            warmStarter().run(null);

            verify(backfillDensityMetrics).warmBelowFloorCount(WatermarkSeries.DAILY_OHLCV_KRX, 3L);
        }

        @Test
        @DisplayName("US internal_gap Redis 값이 있으면 warmInternalGapCount 호출")
        void seedsInternalGapFromRedis() {
            lenient()
                    .when(backfillDensityRepository.findInternalGap("daily-ohlcv-us"))
                    .thenReturn(Optional.of(5L));

            warmStarter().run(null);

            verify(backfillDensityMetrics).warmInternalGapCount(WatermarkSeries.DAILY_OHLCV_US, 5L);
        }

        @Test
        @DisplayName("게이지 2종 × 시리즈 2종 = 4조합 전부 조회된다")
        void queriesAllFourCombinations() {
            when(backfillDensityRepository.findBelowFloor(anyString()))
                    .thenReturn(Optional.empty());
            when(backfillDensityRepository.findInternalGap(anyString()))
                    .thenReturn(Optional.empty());

            warmStarter().run(null);

            verify(backfillDensityRepository).findBelowFloor("daily-ohlcv-krx");
            verify(backfillDensityRepository).findBelowFloor("daily-ohlcv-us");
            verify(backfillDensityRepository).findInternalGap("daily-ohlcv-krx");
            verify(backfillDensityRepository).findInternalGap("daily-ohlcv-us");
        }
    }

    @Nested
    @DisplayName("Redis 값 부재 — seed 생략, 0 유지 (REQ-WSR4-002)")
    class RedisValueAbsent {

        @Test
        @DisplayName("모든 조합의 Redis 값이 없으면 warm* 이 한 번도 호출되지 않는다")
        void skipsAllWhenRedisEmpty() {
            warmStarter().run(null);

            verify(backfillDensityMetrics, never()).warmBelowFloorCount(any(), anyLong());
            verify(backfillDensityMetrics, never()).warmInternalGapCount(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 조합 조회 실패 시 나머지 계속 처리 (REQ-WSR-024 계승)")
    class FailureIsolation {

        @Test
        @DisplayName("below_floor 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneCombinationThrows() {
            lenient()
                    .when(backfillDensityRepository.findBelowFloor("daily-ohlcv-krx"))
                    .thenThrow(new QueryTimeoutException("Redis 연결 실패"));

            assertThatNoException().isThrownBy(() -> warmStarter().run(null));
        }

        @Test
        @DisplayName("KRX below_floor 조회 실패해도 US internal_gap seed는 계속된다")
        void warmsContinuingCombinationsAfterOneFailure() {
            lenient()
                    .when(backfillDensityRepository.findBelowFloor("daily-ohlcv-krx"))
                    .thenThrow(new QueryTimeoutException("Redis 오류")); // KRX below-floor 실패
            lenient()
                    .when(backfillDensityRepository.findInternalGap("daily-ohlcv-us"))
                    .thenReturn(Optional.of(9L)); // US internal-gap 성공

            warmStarter().run(null);

            verify(backfillDensityMetrics)
                    .warmInternalGapCount(eq(WatermarkSeries.DAILY_OHLCV_US), eq(9L));
        }
    }
}
