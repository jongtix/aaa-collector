package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.CoverageMetrics;
import com.aaa.collector.observability.CoverageRatioRepository;
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
 * {@link CoverageMetricsWarmStarter} — Redis 단일 소스 warm-start (SPEC-COLLECTOR-WARMSTART-REDIS-004).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
        "CoverageMetricsWarmStarter — Redis 단일 소스 data_coverage_ratio gauge 초기화"
                + " (SPEC-COLLECTOR-WARMSTART-REDIS-004)")
class CoverageMetricsWarmStarterTest {

    @Mock private CoverageMetrics coverageMetrics;
    @Mock private CoverageRatioRepository coverageRatioRepository;

    private CoverageMetricsWarmStarter warmStarter() {
        return new CoverageMetricsWarmStarter(coverageMetrics, coverageRatioRepository);
    }

    @Nested
    @DisplayName("Redis 값 존재 — data_coverage_ratio seed (REQ-WSR4-001)")
    class RedisValuePresent {

        @Test
        @DisplayName("KRX Redis 비율이 있으면 warmRatio(DAILY_OHLCV_KRX, ratio) 호출")
        void seedsFromRedisWhenValuePresent() {
            lenient()
                    .when(coverageRatioRepository.find("daily-ohlcv-krx"))
                    .thenReturn(Optional.of(0.95));

            warmStarter().run(null);

            verify(coverageMetrics).warmRatio(WatermarkSeries.DAILY_OHLCV_KRX, 0.95);
        }

        @Test
        @DisplayName("US Redis 비율이 있으면 동일하게 seed된다")
        void seedsFromRedisForUs() {
            lenient()
                    .when(coverageRatioRepository.find("daily-ohlcv-us"))
                    .thenReturn(Optional.of(0.5));

            warmStarter().run(null);

            verify(coverageMetrics).warmRatio(WatermarkSeries.DAILY_OHLCV_US, 0.5);
        }

        @Test
        @DisplayName("KRX/US 2 시리즈 전부 조회된다(하드코딩 정본 순회)")
        void queriesBothSeries() {
            when(coverageRatioRepository.find(anyString())).thenReturn(Optional.empty());

            warmStarter().run(null);

            verify(coverageRatioRepository).find("daily-ohlcv-krx");
            verify(coverageRatioRepository).find("daily-ohlcv-us");
        }
    }

    @Nested
    @DisplayName("Redis 값 부재 — seed 생략, 0.0 유지, 프록시 대체 없음 (REQ-WSR4-001)")
    class RedisValueAbsent {

        @Test
        @DisplayName("모든 시리즈의 Redis 값이 없으면 warmRatio가 한 번도 호출되지 않는다")
        void skipsAllWhenRedisEmpty() {
            warmStarter().run(null);

            verify(coverageMetrics, never())
                    .warmRatio(any(), org.mockito.ArgumentMatchers.anyDouble());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 시리즈 Redis 조회 실패 시 나머지 계속 처리 (REQ-WSR-024 계승)")
    class FailureIsolation {

        @Test
        @DisplayName("Redis 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneSeriesThrows() {
            lenient()
                    .when(coverageRatioRepository.find("daily-ohlcv-krx"))
                    .thenThrow(new QueryTimeoutException("Redis 연결 실패"));

            assertThatNoException().isThrownBy(() -> warmStarter().run(null));
        }

        @Test
        @DisplayName("한 시리즈 조회가 실패해도 나머지 시리즈는 warmRatio 호출이 계속된다")
        void warmsContinuingSeriesAfterOneFailure() {
            lenient()
                    .when(coverageRatioRepository.find("daily-ohlcv-krx"))
                    .thenThrow(new QueryTimeoutException("Redis 오류")); // KRX 실패
            lenient()
                    .when(coverageRatioRepository.find("daily-ohlcv-us"))
                    .thenReturn(Optional.of(0.8)); // US 성공

            warmStarter().run(null);

            verify(coverageMetrics).warmRatio(eq(WatermarkSeries.DAILY_OHLCV_US), eq(0.8));
        }
    }
}
