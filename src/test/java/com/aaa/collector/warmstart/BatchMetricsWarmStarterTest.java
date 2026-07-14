package com.aaa.collector.warmstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.observability.BatchLastLoadRepository;
import com.aaa.collector.observability.BatchMetrics;
import com.aaa.collector.stock.ShortSaleOverseasRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * {@link BatchMetricsWarmStarter} — Redis 단일 소스 warm-start (SPEC-COLLECTOR-WARMSTART-REDIS-002).
 *
 * <p>1단계(SPEC-COLLECTOR-WARMSTART-REDIS-001)의 Redis 우선·DB 프록시 폴백 구조에서 프록시 폴백을 완전히 제거한 2단계 결과를 검증한다.
 * {@code last_load} seed의 유일 소스는 {@link BatchLastLoadRepository}(Redis)이며, 값 부재 시 게이지를 seed하지
 * 않는다(REQ-WSR-010/011/012). {@code last_data}(overseas-shortsale-interest) 경로만 {@link
 * ShortSaleOverseasRepository} 프록시를 그대로 유지한다(REQ-WSR-014, 불변).
 *
 * <p>{@code run()}은 레지스트리 편입 배치 전수(23종)에 대해 {@link BatchLastLoadRepository#find}를 호출하므로, 개별 테스트에서
 * 특정 라벨만 stub할 때는 strict stubbing의 argument-mismatch 오탐(다른 라벨 호출)을 피하기 위해 {@code lenient()}를 사용한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName(
        "BatchMetricsWarmStarter — Redis 단일 소스 last-load gauge 초기화"
                + " (SPEC-COLLECTOR-WARMSTART-REDIS-002)")
class BatchMetricsWarmStarterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private BatchMetrics batchMetrics;
    @Mock private BatchLastLoadRepository batchLastLoadRepository;
    @Mock private ShortSaleOverseasRepository shortSaleOverseasRepository;

    private BatchMetricsWarmStarter warmStarter() {
        return new BatchMetricsWarmStarter(
                batchMetrics, batchLastLoadRepository, shortSaleOverseasRepository);
    }

    @Nested
    @DisplayName("Redis 값 존재 — last_load seed (REQ-WSR-011, AC-1)")
    class RedisValuePresent {

        @Test
        @DisplayName("corp-code Redis 성공 epoch가 있으면 warmLastLoad('corp-code', instant) 호출")
        void seedsFromRedisWhenValuePresent() {
            // Arrange — 성공-0건-삽입 배치(corp-code)의 프록시 부동이 근원 소멸했음을 검증하는 대표 케이스
            Instant redisInstant = Instant.ofEpochSecond(1_752_000_000L);
            lenient()
                    .when(batchLastLoadRepository.find("corp-code"))
                    .thenReturn(Optional.of(redisInstant));

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics).warmLastLoad("corp-code", redisInstant);
        }

        @Test
        @DisplayName("Redis 성공 epoch가 있으면 레지스트리 편입 배치 어디든 동일하게 seed된다")
        void seedsFromRedisForAnyRegisteredBatch() {
            // Arrange
            Instant redisInstant = Instant.ofEpochSecond(1_752_500_000L);
            lenient()
                    .when(batchLastLoadRepository.find("watchlist-sync-krx"))
                    .thenReturn(Optional.of(redisInstant));

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics).warmLastLoad("watchlist-sync-krx", redisInstant);
        }
    }

    @Nested
    @DisplayName("Redis 값 부재 — seed 생략, 프록시 대체 없음 (REQ-WSR-010/012, AC-2)")
    class RedisValueAbsent {

        @Test
        @DisplayName("모든 배치의 Redis 값이 없으면 warmLastLoad가 한 번도 호출되지 않는다")
        void skipsAllWhenRedisEmpty() {
            // Arrange — Mockito 기본값(Optional.empty())을 그대로 사용

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics, never()).warmLastLoad(any(), any());
        }

        @Test
        @DisplayName("Redis 값이 없는 배치는 DB 프록시로 대체 seed되지 않는다(프록시 배선 자체가 존재하지 않음)")
        void doesNotFallBackToProxy() {
            // Arrange — corp-code Redis 값 없음(기본값)

            // Act
            warmStarter().run(null);

            // Assert — corp-code에 대해 어떤 값으로도 warmLastLoad가 호출되지 않는다(부재 유지)
            verify(batchMetrics, never()).warmLastLoad(eq("corp-code"), any());
        }
    }

    @Nested
    @DisplayName("실패 격리 — 한 배치 Redis 조회 실패 시 나머지 계속 처리")
    class FailureIsolation {

        @Test
        @DisplayName("Redis 조회에서 DataAccessException 발생해도 run()이 예외 없이 완료된다")
        void continuesWhenOneBatchThrows() {
            // Arrange
            lenient()
                    .when(batchLastLoadRepository.find("domestic-daily"))
                    .thenThrow(new QueryTimeoutException("Redis 연결 실패"));

            // Act & Assert
            assertThatNoException().isThrownBy(() -> warmStarter().run(null));
        }

        @Test
        @DisplayName("한 배치 Redis 조회가 실패해도 나머지 배치는 warmLastLoad 호출이 계속된다")
        void warmsContinuingBatchesAfterOneFailure() {
            // Arrange
            Instant redisInstant = Instant.ofEpochSecond(1_751_000_000L);
            lenient()
                    .when(batchLastLoadRepository.find("domestic-daily"))
                    .thenThrow(new QueryTimeoutException("Redis 오류")); // domestic-daily 실패
            lenient()
                    .when(batchLastLoadRepository.find("overseas-daily"))
                    .thenReturn(Optional.of(redisInstant)); // overseas-daily 성공

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics).warmLastLoad(eq("overseas-daily"), eq(redisInstant));
        }
    }

    @Nested
    @DisplayName("last_data 경로(overseas-shortsale-interest) — DB 프록시 불변 (REQ-WSR-014, AC-4)")
    class InterestDataSeedUnchanged {

        @Test
        @DisplayName("findMaxInterestCollectedAt 결과가 있으면 warmDataArrival(interest, instant) 호출")
        void seedsInterestLastData() {
            // Arrange
            LocalDateTime kstTime = LocalDateTime.of(2026, 4, 16, 6, 0, 0);
            Instant expected = kstTime.atZone(KST).toInstant();
            when(shortSaleOverseasRepository.findMaxInterestCollectedAt())
                    .thenReturn(Optional.of(kstTime));

            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics).warmDataArrival(eq("overseas-shortsale-interest"), eq(expected));
        }

        @Test
        @DisplayName("interest 조회 결과가 없으면 warmDataArrival을 호출하지 않는다")
        void skipsSeedWhenEmpty() {
            // Act
            warmStarter().run(null);

            // Assert
            verify(batchMetrics, never()).warmDataArrival(eq("overseas-shortsale-interest"), any());
        }

        @Test
        @DisplayName("last_data seed는 last_load(Redis) 경로와 독립적으로 함께 동작한다")
        void lastDataAndLastLoadCoexist() {
            // Arrange — last_load는 Redis에서, last_data는 여전히 DB 프록시에서 seed되어야 한다
            Instant redisInstant = Instant.ofEpochSecond(1_752_600_000L);
            LocalDateTime dataTime = LocalDateTime.of(2026, 4, 16, 6, 0, 0);
            Instant dataInstant = dataTime.atZone(KST).toInstant();
            lenient()
                    .when(batchLastLoadRepository.find("overseas-shortsale-interest"))
                    .thenReturn(Optional.of(redisInstant));
            when(shortSaleOverseasRepository.findMaxInterestCollectedAt())
                    .thenReturn(Optional.of(dataTime));

            // Act
            warmStarter().run(null);

            // Assert — last_load는 Redis 값, last_data는 프록시 값(불변)
            verify(batchMetrics).warmLastLoad("overseas-shortsale-interest", redisInstant);
            verify(batchMetrics).warmDataArrival("overseas-shortsale-interest", dataInstant);
        }
    }

    @Nested
    @DisplayName("보증 — warm-starter가 프록시 리포지토리/WarmSource에 의존하지 않는다 (REQ-WSR-013, AC-3)")
    class NoProxyDependency {

        @Test
        @DisplayName(
                "생성자는 BatchMetrics·BatchLastLoadRepository·ShortSaleOverseasRepository 3개만 받는다")
        void constructorHasThreeCollaboratorsOnly() {
            assertThat(BatchMetricsWarmStarter.class.getDeclaredConstructors()).hasSize(1);
            assertThat(
                            BatchMetricsWarmStarter.class.getDeclaredConstructors()[0]
                                    .getParameterCount())
                    .isEqualTo(3);
        }
    }
}
