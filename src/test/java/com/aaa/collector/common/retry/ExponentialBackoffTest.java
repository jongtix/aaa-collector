package com.aaa.collector.common.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ExponentialBackoff")
class ExponentialBackoffTest {

    @Nested
    @DisplayName("delay() - 정상 계산")
    class NormalCalculationTest {

        @Test
        @DisplayName("attempt=0 이면 baseDelay 그대로 반환한다")
        void attempt0_returnsBaseDelay() {
            assertThat(ExponentialBackoff.delay(0, 1000)).isEqualTo(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("attempt=1 이면 baseDelay * 2를 반환한다")
        void attempt1_returnsDoubleBaseDelay() {
            assertThat(ExponentialBackoff.delay(1, 1000)).isEqualTo(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("attempt=2 이면 baseDelay * 4를 반환한다")
        void attempt2_returnsQuadrupleBaseDelay() {
            assertThat(ExponentialBackoff.delay(2, 1000)).isEqualTo(Duration.ofSeconds(4));
        }
    }

    @Nested
    @DisplayName("delay() - MAX_DELAY_MS 캡")
    class MaxDelayCapTest {

        @Test
        @DisplayName("계산값이 60초를 초과하면 60초로 캡핑한다")
        void largeAttempt_capsAt60Seconds() {
            assertThat(ExponentialBackoff.delay(100, 1000)).isEqualTo(Duration.ofSeconds(60));
        }

        @Test
        @DisplayName("비트 시프트 오버플로우로 음수가 되는 경우에도 60초로 캡핑한다")
        void overflowAttempt_capsAt60Seconds() {
            // attempt=63 이상이면 1L << attempt 가 음수 또는 0으로 오버플로우
            assertThat(ExponentialBackoff.delay(63, 1000)).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Nested
    @DisplayName("delay() - 유효성 검사")
    class ValidationTest {

        @Test
        @DisplayName("attempt < 0 이면 IllegalArgumentException 을 던진다")
        void negativeAttempt_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> ExponentialBackoff.delay(-1, 1000))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attempt");
        }

        @Test
        @DisplayName("baseDelayMs = 0 이면 IllegalArgumentException 을 던진다")
        void zeroBaseDelay_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> ExponentialBackoff.delay(0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseDelayMs");
        }

        @Test
        @DisplayName("baseDelayMs < 0 이면 IllegalArgumentException 을 던진다")
        void negativeBaseDelay_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> ExponentialBackoff.delay(0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseDelayMs");
        }
    }
}
