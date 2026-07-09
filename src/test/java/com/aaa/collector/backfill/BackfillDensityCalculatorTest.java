package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.backfill.BackfillDensityCalculator.StockDensityInput;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link BackfillDensityCalculator} 순수 로직 단위 테스트 (SPEC-COLLECTOR-BACKFILL-010 AC-7, AC-8). */
@DisplayName("BackfillDensityCalculator — 밀도 게이지 A/B 순수 계산 (SPEC-COLLECTOR-BACKFILL-010)")
class BackfillDensityCalculatorTest {

    @Nested
    @DisplayName("countBelowFloor — 게이지 A (REQ-154, AC-7)")
    class CountBelowFloor {

        @Test
        @DisplayName("신뢰 하한 존재 + MIN(trade_date) > floor → 카운트")
        void trustedFloor_belowFloor_counted() {
            StockDensityInput belowFloor =
                    new StockDensityInput(
                            LocalDate.of(2010, 1, 1),
                            LocalDate.of(2020, 1, 1),
                            100,
                            LocalDate.of(2007, 8, 20));

            long count = BackfillDensityCalculator.countBelowFloor(List.of(belowFloor));

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("신뢰 하한 존재 + MIN(trade_date) == floor(도달) → 미카운트")
        void trustedFloor_atFloor_notCounted() {
            LocalDate floor = LocalDate.of(2007, 8, 20);
            StockDensityInput atFloor =
                    new StockDensityInput(floor, LocalDate.of(2020, 1, 1), 100, floor);

            long count = BackfillDensityCalculator.countBelowFloor(List.of(atFloor));

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("신뢰 하한 부재(trustedFloor=null, 미검증 레거시) → 모집단 제외(EC-5)")
        void noTrustedFloor_excludedFromPopulation() {
            StockDensityInput noFloor =
                    new StockDensityInput(
                            LocalDate.of(2019, 12, 17), LocalDate.of(2020, 1, 1), 10, null);

            long count = BackfillDensityCalculator.countBelowFloor(List.of(noFloor));

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("데이터 없음(minTradeDate=null) → 모집단 제외")
        void noData_excluded() {
            StockDensityInput noData =
                    new StockDensityInput(null, null, 0, LocalDate.of(2007, 8, 20));

            long count = BackfillDensityCalculator.countBelowFloor(List.of(noData));

            assertThat(count).isZero();
        }
    }

    @Nested
    @DisplayName("countInternalGaps — 게이지 B (REQ-155, AC-8)")
    class CountInternalGaps {

        @Test
        @DisplayName("구간 내 캘린더 존재·자신 부재 거래일 있음(rowCount < 캘린더 구간 카운트) → 카운트")
        void gapExists_counted() {
            List<LocalDate> calendar =
                    List.of(
                            LocalDate.of(2020, 1, 1),
                            LocalDate.of(2020, 1, 2),
                            LocalDate.of(2020, 1, 3),
                            LocalDate.of(2020, 1, 4));
            // 구간 [1,1 ~ 1,4] 안에 캘린더 4일 존재하나 보유 3행 — 1일 결측
            StockDensityInput gap =
                    new StockDensityInput(
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 4), 3, null);

            long count = BackfillDensityCalculator.countInternalGaps(List.of(gap), calendar);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("구멍 없음(rowCount == 캘린더 구간 카운트) → 미카운트")
        void noGap_notCounted() {
            List<LocalDate> calendar =
                    List.of(
                            LocalDate.of(2020, 1, 1),
                            LocalDate.of(2020, 1, 2),
                            LocalDate.of(2020, 1, 3));
            StockDensityInput noGap =
                    new StockDensityInput(
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3), 3, null);

            long count = BackfillDensityCalculator.countInternalGaps(List.of(noGap), calendar);

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("신뢰 하한 불필요 — trustedFloor=null(미검증 레거시)도 감시 대상(레거시 포함)")
        void legacyWithoutTrustedFloor_stillMonitored() {
            List<LocalDate> calendar = List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));
            StockDensityInput legacyGap =
                    new StockDensityInput(
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2), 1, null);

            long count = BackfillDensityCalculator.countInternalGaps(List.of(legacyGap), calendar);

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("데이터 없음(min/max=null) → 미카운트")
        void noData_notCounted() {
            List<LocalDate> calendar = List.of(LocalDate.of(2020, 1, 1));
            StockDensityInput noData = new StockDensityInput(null, null, 0, null);

            long count = BackfillDensityCalculator.countInternalGaps(List.of(noData), calendar);

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("전 종목 공통 부재일은 캘린더에서 자체 제외되므로 구멍으로 세지 않는다(§A10)")
        void universallyAbsentDate_notInCalendarSoNotCounted() {
            // 캘린더 자체가 1/1, 1/3만 있음(1/2는 전 종목 공통 휴장·부재 → 캘린더 생성 단계에서 이미 제외됨을 가정)
            List<LocalDate> calendar = List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3));
            StockDensityInput stock =
                    new StockDensityInput(
                            LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3), 2, null);

            long count = BackfillDensityCalculator.countInternalGaps(List.of(stock), calendar);

            assertThat(count).isZero();
        }
    }
}
