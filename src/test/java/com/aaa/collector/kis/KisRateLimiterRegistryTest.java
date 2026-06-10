package com.aaa.collector.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KisRateLimiterRegistryTest {

    private static final KisProperties.RateLimit RATE_LIMIT =
            new KisProperties.RateLimit(3, 15, 10);

    private static KisProperties propsWithAliases(String... aliases) {
        List<KisAccountCredential> accounts =
                java.util.Arrays.stream(aliases)
                        .map(a -> new KisAccountCredential(a, "12345678", "appkey", "appsecret"))
                        .toList();
        return new KisProperties(
                "https://openapi.koreainvestment.com:9443", "user", accounts, RATE_LIMIT);
    }

    @Nested
    @DisplayName("forAlias — 등록된 alias 조회")
    class ForAlias {

        @Test
        @DisplayName("등록된 alias — non-null KisRateLimiter 반환")
        void forAlias_registeredAlias_returnsNonNull() {
            KisRateLimiterRegistry registry = new KisRateLimiterRegistry(propsWithAliases("isa"));

            KisRateLimiter limiter = registry.forAlias("isa");

            assertThat(limiter).isNotNull();
        }

        @Test
        @DisplayName("5개 alias 등록 — 각 alias가 독립된 limiter 인스턴스")
        void forAlias_fiveAliases_returnsDistinctInstances() {
            // Arrange
            KisRateLimiterRegistry registry =
                    new KisRateLimiterRegistry(
                            propsWithAliases("isa", "gold", "pension", "stock", "dc"));

            // Act
            KisRateLimiter isa = registry.forAlias("isa");
            KisRateLimiter gold = registry.forAlias("gold");
            KisRateLimiter pension = registry.forAlias("pension");
            KisRateLimiter stock = registry.forAlias("stock");
            KisRateLimiter dc = registry.forAlias("dc");

            // Assert
            assertThat(isa).isNotSameAs(gold);
            assertThat(gold).isNotSameAs(pension);
            assertThat(pension).isNotSameAs(stock);
            assertThat(stock).isNotSameAs(dc);
        }

        @Test
        @DisplayName("미등록 alias — IllegalArgumentException")
        void forAlias_unknownAlias_throwsIllegalArgumentException() {
            KisRateLimiterRegistry registry = new KisRateLimiterRegistry(propsWithAliases("isa"));

            assertThatThrownBy(() -> registry.forAlias("unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown");
        }
    }

    @Nested
    @DisplayName("키별 독립 동작")
    class Independence {

        @Test
        @DisplayName("두 alias의 limiter — 한 키 소진이 다른 키를 막지 않음 (AC-2 S2-1)")
        void consume_twoAliases_exhaustOneDoesNotBlockOther() throws InterruptedException {
            // Arrange: capacity=3, refillPerSecond=1 (리필 느림)
            KisProperties.RateLimit config = new KisProperties.RateLimit(3, 1, 5);
            List<KisAccountCredential> accounts =
                    List.of(
                            new KisAccountCredential("isa", "12345678", "k1", "s1"),
                            new KisAccountCredential("gold", "87654321", "k2", "s2"));
            KisProperties props =
                    new KisProperties(
                            "https://openapi.koreainvestment.com:9443", "user", accounts, config);
            KisRateLimiterRegistry registry = new KisRateLimiterRegistry(props);

            KisRateLimiter isaLimiter = registry.forAlias("isa");
            KisRateLimiter goldLimiter = registry.forAlias("gold");

            // Exhaust isa's capacity (3 tokens)
            for (int i = 0; i < 3; i++) {
                isaLimiter.consume();
                isaLimiter.release();
            }

            // Act & Assert: gold limiter should still have tokens available (tryConsume
            // non-blocking)
            boolean goldTokenAvailable = goldLimiter.getBucket().tryConsume(1);
            assertThat(goldTokenAvailable)
                    .as("gold limiter is independent — isa exhaustion should not affect gold")
                    .isTrue();
        }
    }
}
