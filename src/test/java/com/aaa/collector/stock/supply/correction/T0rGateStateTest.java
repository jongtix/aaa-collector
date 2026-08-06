package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link T0rGateState} 단위 테스트 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-T0R-043~045,
 * plan.md §M7).
 */
@DisplayName("T0rGateState 단위 테스트")
class T0rGateStateTest {

    @Nested
    @DisplayName("inactive() — REQ-T0R-045")
    class Inactive {

        @Test
        @DisplayName("active=false, closingWindowEndDate=null")
        void inactive_hasActiveFalseAndNullDate() {
            T0rGateState gate = T0rGateState.inactive();

            assertThat(gate.active()).isFalse();
            assertThat(gate.closingWindowEndDate()).isNull();
        }

        @Test
        @DisplayName("shouldDefer는 어떤 거래일에도 항상 false를 반환한다(구간 검사 생략)")
        void inactive_shouldDeferAlwaysFalse() {
            T0rGateState gate = T0rGateState.inactive();

            assertThat(gate.shouldDefer(LocalDate.of(2026, 6, 29))).isFalse();
            assertThat(gate.shouldDefer(LocalDate.of(2026, 8, 6))).isFalse();
            assertThat(gate.shouldDefer(LocalDate.of(2020, 1, 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("active — REQ-T0R-044 구간 경계")
    class ActiveBoundary {

        private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 6);

        @Test
        @DisplayName("하한(2026-06-29) 직전 — defer 아님")
        void justBeforeLowerBound_notDeferred() {
            T0rGateState gate = new T0rGateState(true, WINDOW_END);

            assertThat(gate.shouldDefer(LocalDate.of(2026, 6, 28))).isFalse();
        }

        @Test
        @DisplayName("하한(2026-06-29) — inclusive, defer 대상")
        void atLowerBound_deferred() {
            T0rGateState gate = new T0rGateState(true, WINDOW_END);

            assertThat(gate.shouldDefer(LocalDate.of(2026, 6, 29))).isTrue();
        }

        @Test
        @DisplayName("구간 내부 — defer 대상")
        void withinWindow_deferred() {
            T0rGateState gate = new T0rGateState(true, WINDOW_END);

            assertThat(gate.shouldDefer(LocalDate.of(2026, 7, 20))).isTrue();
        }

        @Test
        @DisplayName("상한(closingWindowEndDate) — inclusive, defer 대상")
        void atUpperBound_deferred() {
            T0rGateState gate = new T0rGateState(true, WINDOW_END);

            assertThat(gate.shouldDefer(WINDOW_END)).isTrue();
        }

        @Test
        @DisplayName("상한 직후 — defer 아님(정상 처리)")
        void justAfterUpperBound_notDeferred() {
            T0rGateState gate = new T0rGateState(true, WINDOW_END);

            assertThat(gate.shouldDefer(WINDOW_END.plusDays(1))).isFalse();
        }
    }
}
