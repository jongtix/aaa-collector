package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 백필 윈도우 anchor 전진기 명세 (SPEC-COLLECTOR-BACKFILL-001 T5, SPEC-COLLECTOR-BACKFILL-005 T2).
 *
 * <p>순수 로직 — KIS/Spring 비의존. floorDate·anchor-skip-max는 생성자 주입.
 */
class BackfillWindowAdvancerTest {

    private static final LocalDate FLOOR_DATE = LocalDate.of(1950, 1, 1);
    private static final int ANCHOR_SKIP_MAX = 10;
    private final BackfillWindowAdvancer advancer =
            new BackfillWindowAdvancer(FLOOR_DATE, ANCHOR_SKIP_MAX);

    @Nested
    @DisplayName("anchor 전진 — −1 달력일 (AC-3.2, MA-02)")
    class NextAnchor {

        @Test
        @DisplayName("AC-3.2: oldest=금 2024-06-07이면 다음 anchor=2024-06-06 (−1 달력일)")
        void friday_minusOneCalendarDay() {
            LocalDate next = advancer.nextAnchor(LocalDate.of(2024, 6, 7));

            assertThat(next).isEqualTo(LocalDate.of(2024, 6, 6));
        }

        @Test
        @DisplayName("−1 달력일은 영업일 계산이 아님 — 월요일이면 일요일로 떨어져도 그대로")
        void monday_fallsOnSunday() {
            // 2024-06-10(월) → 2024-06-09(일). KIS가 이전 거래일을 반환하므로 무해.
            LocalDate next = advancer.nextAnchor(LocalDate.of(2024, 6, 10));

            assertThat(next).isEqualTo(LocalDate.of(2024, 6, 9));
        }
    }

    @Nested
    @DisplayName("그룹 A 고정 플로어 — anchor 무관 floorDate 반환 (AC-2, SPEC-COLLECTOR-BACKFILL-005)")
    class GroupAFromDate {

        @Test
        @DisplayName("AC-2: groupAFromDate()는 anchor와 무관하게 항상 floorDate(1950-01-01) 반환")
        void fromDate_alwaysReturnFloorDate() {
            LocalDate from = advancer.groupAFromDate();

            assertThat(from).isEqualTo(LocalDate.of(1950, 1, 1));
        }

        @Test
        @DisplayName("AC-2: 다양한 시점에서 호출해도 floorDate는 변하지 않음")
        void fromDate_anchorIndependent() {
            LocalDate first = advancer.groupAFromDate();
            LocalDate second = advancer.groupAFromDate();

            assertThat(first).isEqualTo(second).isEqualTo(LocalDate.of(1950, 1, 1));
        }

        @Test
        @DisplayName("floorDate=2000-01-01로 생성 시 groupAFromDate()도 2000-01-01 반환")
        void fromDate_respectsCustomFloor() {
            BackfillWindowAdvancer customAdvancer =
                    new BackfillWindowAdvancer(LocalDate.of(2000, 1, 1), ANCHOR_SKIP_MAX);

            LocalDate from = customAdvancer.groupAFromDate();

            assertThat(from).isEqualTo(LocalDate.of(2000, 1, 1));
        }
    }

    @Nested
    @DisplayName(
            "그룹 B 첫 probe 구간 backward advance (SPEC-COLLECTOR-BACKFILL-013 AC-4/AC-6, REQ-BACKFILL-164)")
    class NextGroupBProbeAnchor {

        @Test
        @DisplayName("short_sale_domestic — stride(90일)만큼 과거로 전진 — floor 미도달")
        void advancesByStride_whenAboveFloor() {
            LocalDate anchor = LocalDate.of(2020, 1, 1);
            LocalDate floor = LocalDate.of(1985, 1, 4);

            LocalDate next = advancer.nextGroupBProbeAnchor("short_sale_domestic", anchor, floor);

            assertThat(next).isEqualTo(LocalDate.of(2019, 10, 3));
        }

        @Test
        @DisplayName("investor_trend/credit_balance — stride(45일)만큼 과거로 전진 — floor 미도달")
        void advancesByStride_investorAndCredit() {
            LocalDate anchor = LocalDate.of(2020, 1, 1);
            LocalDate floor = LocalDate.of(1985, 1, 4);
            LocalDate expected = LocalDate.of(2019, 11, 17);

            assertThat(advancer.nextGroupBProbeAnchor("investor_trend", anchor, floor))
                    .isEqualTo(expected);
            assertThat(advancer.nextGroupBProbeAnchor("credit_balance", anchor, floor))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("전진 결과가 floor 미만이면 floor로 clamp")
        void clampsToFloor_whenAdvanceCrossesFloor() {
            LocalDate anchor = LocalDate.of(1985, 2, 1);
            LocalDate floor = LocalDate.of(1985, 1, 4);

            LocalDate next = advancer.nextGroupBProbeAnchor("credit_balance", anchor, floor);

            assertThat(next).isEqualTo(floor);
        }

        @Test
        @DisplayName("anchor가 이미 floor면 floor 유지(clamp)")
        void anchorAlreadyAtFloor_staysAtFloor() {
            LocalDate floor = LocalDate.of(1985, 1, 4);

            LocalDate next = advancer.nextGroupBProbeAnchor("short_sale_domestic", floor, floor);

            assertThat(next).isEqualTo(floor);
        }
    }

    @Nested
    @DisplayName("그룹 B anchor 거부 보정 — rt_cd=2 (AC-3.3, REQ-016)")
    class RejectedAnchorCorrection {

        @Test
        @DisplayName("AC-3.3: 토요일 anchor rt_cd=2면 −1 달력일 보정하고 무전진 카운트 안 함")
        void saturdayRejected_correctsMinusOneDay() {
            // 2024-06-08(토) anchor가 rt_cd=2로 거부됨
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 8), 0);

            assertThat(result.correctedAnchor()).isEqualTo(LocalDate.of(2024, 6, 7));
            assertThat(result.exhausted()).isFalse();
            assertThat(result.attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("보정은 매번 1씩 누적 — 직전 attempts 기반으로 증가")
        void correction_accumulatesAttempts() {
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 5), 4);

            assertThat(result.attempts()).isEqualTo(5);
            assertThat(result.exhausted()).isFalse();
        }

        @Test
        @DisplayName("AC-3.3: anchor-skip-max(10) 초과 시 exhausted=true → 당 회차 skip")
        void exhaustsAtSkipMax() {
            // attemptsSoFar=9 → 10번째 보정 → 한도 도달 → exhausted
            AnchorCorrectionResult result =
                    advancer.correctRejectedAnchor(LocalDate.of(2024, 6, 1), 9);

            assertThat(result.attempts()).isEqualTo(10);
            assertThat(result.exhausted()).isTrue();
        }
    }
}
