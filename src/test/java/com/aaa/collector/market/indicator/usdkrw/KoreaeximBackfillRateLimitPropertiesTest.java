package com.aaa.collector.market.indicator.usdkrw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * KOREAEXIM 백필 rate-limit 설정 바인딩·기본값·검증 명세 (SPEC-COLLECTOR-MARKETIND-007 AC-04).
 *
 * <p>기본값(capacity=3, refill-per-second=15, max-concurrency=10)은 기존 {@code kis.rate-limit} 프로덕션 설정을
 * 그대로 미러한 값이다(research §7 OQ-3 RESOLVED) — KOREAEXIM 전용으로 새로 보수화한 값이 아니다.
 */
class KoreaeximBackfillRateLimitPropertiesTest {

    private static final String PREFIX = "aaa.market-indicator.backfill.usdkrw.rate-limit";

    @Nested
    @DisplayName("설정 바인딩 (AC-04)")
    class Binding {

        @Test
        @DisplayName("설정 키 부재 — KIS 프로덕션 미러 기본값(capacity=3/refill=15/concurrency=10) 적용")
        void bind_noKeysConfigured_appliesKisMirrorDefaults() {
            // bindOrCreate: 실 프로덕션에서 @ConfigurationPropertiesScan이 constructor-bound 레코드를
            // 생성할 때 사용하는 것과 동일한 경로 — 설정 키가 전혀 없어도 @DefaultValue로 인스턴스를 생성한다.
            Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

            KoreaeximBackfillRateLimitProperties props =
                    binder.bindOrCreate(PREFIX, KoreaeximBackfillRateLimitProperties.class);

            assertThat(props.capacity()).isEqualTo(3);
            assertThat(props.refillPerSecond()).isEqualTo(15);
            assertThat(props.maxConcurrency()).isEqualTo(10);
        }

        @Test
        @DisplayName("설정 키 존재 — 바인딩된 값이 버킷/세마포어 설정에 그대로 반영")
        void bind_keysConfigured_appliesConfiguredValues() {
            Map<String, Object> source =
                    Map.of(
                            PREFIX + ".capacity", "5",
                            PREFIX + ".refill-per-second", "20",
                            PREFIX + ".max-concurrency", "4");
            Binder binder = new Binder(new MapConfigurationPropertySource(source));

            KoreaeximBackfillRateLimitProperties props =
                    binder.bind(PREFIX, KoreaeximBackfillRateLimitProperties.class).get();

            assertThat(props.capacity()).isEqualTo(5);
            assertThat(props.refillPerSecond()).isEqualTo(20);
            assertThat(props.maxConcurrency()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("양수 검증 — KisProperties.RateLimit 미러 (기동 시 실패)")
    class PositiveValidation {

        @Test
        @DisplayName("capacity=0 — IllegalArgumentException")
        void constructor_zeroCapacity_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new KoreaeximBackfillRateLimitProperties(0, 15, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        @DisplayName("refillPerSecond=0 — IllegalArgumentException")
        void constructor_zeroRefillPerSecond_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new KoreaeximBackfillRateLimitProperties(3, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill-per-second");
        }

        @Test
        @DisplayName("maxConcurrency=0 — IllegalArgumentException")
        void constructor_zeroMaxConcurrency_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> new KoreaeximBackfillRateLimitProperties(3, 15, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max-concurrency");
        }

        @Test
        @DisplayName("3개 필드 전부 잘못됨 — 세 위반 모두 메시지에 포함")
        void constructor_allInvalid_errorMessageContainsAllViolations() {
            assertThatThrownBy(() -> new KoreaeximBackfillRateLimitProperties(-1, -1, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity")
                    .hasMessageContaining("refill-per-second")
                    .hasMessageContaining("max-concurrency");
        }

        @Test
        @DisplayName("정상 값 — 예외 없이 생성")
        void constructor_positiveValues_createsSuccessfully() {
            KoreaeximBackfillRateLimitProperties props =
                    new KoreaeximBackfillRateLimitProperties(3, 15, 10);

            assertThat(props.capacity()).isEqualTo(3);
            assertThat(props.refillPerSecond()).isEqualTo(15);
            assertThat(props.maxConcurrency()).isEqualTo(10);
        }
    }
}
