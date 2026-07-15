package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.market.indicator.MarketIndicatorLastSuccessRepository;
import com.aaa.collector.market.indicator.MarketIndicatorMetrics;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * {@link MarketIndicatorMetricsWarmStarter} — Redis 단일 소스 warm-start
 * (SPEC-COLLECTOR-WARMSTART-REDIS-003).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
        "MarketIndicatorMetricsWarmStarter — Redis 단일 소스 source_last_success gauge 초기화"
                + " (SPEC-COLLECTOR-WARMSTART-REDIS-003)")
class MarketIndicatorMetricsWarmStarterTest {

    @Mock private MarketIndicatorMetrics marketIndicatorMetrics;
    @Mock private MarketIndicatorLastSuccessRepository lastSuccessRepository;

    private MarketIndicatorMetricsWarmStarter warmStarter() {
        return new MarketIndicatorMetricsWarmStarter(marketIndicatorMetrics, lastSuccessRepository);
    }

    @Nested
    @DisplayName("Redis 값 존재 — source_last_success seed (REQ-WSR-021, AC-3)")
    class RedisValuePresent {

        @Test
        @DisplayName("VIX×CBOE Redis 성공 epoch가 있으면 warmLastSuccess('VIX','CBOE', instant) 호출")
        void seedsFromRedisWhenValuePresent() {
            Instant redisInstant = Instant.ofEpochSecond(1_752_000_000L);
            lenient()
                    .when(lastSuccessRepository.find("VIX", "CBOE"))
                    .thenReturn(Optional.of(redisInstant));

            warmStarter().run(null);

            verify(marketIndicatorMetrics).warmLastSuccess("VIX", "CBOE", redisInstant);
        }

        @Test
        @DisplayName("USDKRW×KOREAEXIM Redis 성공 epoch가 있으면 동일하게 seed된다")
        void seedsFromRedisForUsdkrw() {
            Instant redisInstant = Instant.ofEpochSecond(1_752_500_000L);
            lenient()
                    .when(lastSuccessRepository.find("USDKRW", "KOREAEXIM"))
                    .thenReturn(Optional.of(redisInstant));

            warmStarter().run(null);

            verify(marketIndicatorMetrics).warmLastSuccess("USDKRW", "KOREAEXIM", redisInstant);
        }

        @Test
        @DisplayName(
                "4조합 전부 조회된다(KNOWN_SOURCES 단일 소스 순회, REQ-WSR-025; SPEC-COLLECTOR-MARKETIND-003 FRED 제거)")
        void queriesAllFourCombinations() {
            when(lastSuccessRepository.find(any(), any())).thenReturn(Optional.empty());

            warmStarter().run(null);

            verify(lastSuccessRepository).find("VIX", "CBOE");
            verify(lastSuccessRepository).find("VIX", "YAHOO_VIX");
            verify(lastSuccessRepository).find("USDKRW", "KOREAEXIM");
            verify(lastSuccessRepository).find("USDKRW", "YAHOO_USDKRW");
        }
    }

    @Nested
    @DisplayName("Redis 값 부재/손상 — seed 생략, 0.0 유지, 프록시 대체 없음 (REQ-WSR-022, AC-4)")
    class RedisValueAbsent {

        @Test
        @DisplayName("모든 조합의 Redis 값이 없으면 warmLastSuccess가 한 번도 호출되지 않는다")
        void skipsAllWhenRedisEmpty() {
            // Arrange — Mockito 기본값(Optional.empty())을 그대로 사용

            warmStarter().run(null);

            verify(marketIndicatorMetrics, never()).warmLastSuccess(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 조합 Redis 조회 실패 시 나머지 계속 처리 (REQ-WSR-024, AC-5)")
    class FailureIsolation {

        @Test
        @DisplayName("Redis 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneCombinationThrows() {
            lenient()
                    .when(lastSuccessRepository.find("VIX", "CBOE"))
                    .thenThrow(new QueryTimeoutException("Redis 연결 실패"));

            assertThatNoException().isThrownBy(() -> warmStarter().run(null));
        }

        @Test
        @DisplayName("한 조합 Redis 조회가 실패해도 나머지 조합은 warmLastSuccess 호출이 계속된다")
        void warmsContinuingCombinationsAfterOneFailure() {
            Instant redisInstant = Instant.ofEpochSecond(1_751_000_000L);
            lenient()
                    .when(lastSuccessRepository.find("VIX", "CBOE"))
                    .thenThrow(new QueryTimeoutException("Redis 오류")); // VIX×CBOE 실패
            lenient()
                    .when(lastSuccessRepository.find("VIX", "YAHOO_VIX"))
                    .thenReturn(Optional.of(redisInstant)); // VIX×YAHOO_VIX 성공

            warmStarter().run(null);

            verify(marketIndicatorMetrics)
                    .warmLastSuccess(eq("VIX"), eq("YAHOO_VIX"), eq(redisInstant));
        }
    }

    @Nested
    @DisplayName("active_source 미복원 (REQ-WSR-023, AC-10)")
    class ActiveSourceNotRestored {

        @Test
        @DisplayName("run()이 완료돼도 active_source를 복원하는 어떤 메서드도 호출하지 않는다")
        void doesNotRestoreActiveSource() {
            lenient()
                    .when(lastSuccessRepository.find(any(), any()))
                    .thenReturn(Optional.of(Instant.ofEpochSecond(1_752_000_000L)));

            warmStarter().run(null);

            // MarketIndicatorMetrics는 warmLastSuccess만 호출되며, active_source를 건드리는 메서드(예:
            // recordSuccess)는 호출되지 않는다.
            verify(marketIndicatorMetrics, never()).recordSuccess(any(), any());
        }
    }
}
