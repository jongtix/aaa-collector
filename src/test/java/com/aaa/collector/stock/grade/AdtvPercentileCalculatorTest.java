package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AdtvPercentileCalculator 단위 테스트")
class AdtvPercentileCalculatorTest {

    private final AdtvPercentileCalculator calculator = new AdtvPercentileCalculator();

    @Nested
    @DisplayName("calculate — 백분위 계산")
    class Calculate {

        @Test
        @DisplayName("빈 목록 — 빈 map 반환")
        void calculate_emptyList_returnsEmptyMap() {
            Map<String, Double> result = calculator.calculate(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("1개 종목 — 빈 map 반환 (백분위 계산 불가)")
        void calculate_singleStock_returnsEmptyMap() {
            Map<String, Double> result =
                    calculator.calculate(
                            List.of(new AdtvPercentileCalculator.RankEntry("005930", 1000000.0)));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("2개 종목 — 1위는 백분위 낮고(상위), 2위는 높음")
        void calculate_twoStocks_rank1HasLowerPercentile() {
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry(
                                    "005930", 2000000.0), // 1위 (거래금액 높음)
                            new AdtvPercentileCalculator.RankEntry("000660", 1000000.0) // 2위
                            );

            Map<String, Double> result = calculator.calculate(entries);

            assertThat(result).containsKeys("005930", "000660");
            assertThat(result.get("005930")).isLessThan(result.get("000660"));
        }

        @Test
        @DisplayName("5개 종목 — 1위 백분위는 (1/5)*100 = 20")
        void calculate_fiveStocks_rank1Percentile20() {
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry("A", 5000.0),
                            new AdtvPercentileCalculator.RankEntry("B", 4000.0),
                            new AdtvPercentileCalculator.RankEntry("C", 3000.0),
                            new AdtvPercentileCalculator.RankEntry("D", 2000.0),
                            new AdtvPercentileCalculator.RankEntry("E", 1000.0));

            Map<String, Double> result = calculator.calculate(entries);

            assertThat(result.get("A")).isCloseTo(20.0, within(0.01)); // rank 1 of 5 = 20%
            assertThat(result.get("E")).isCloseTo(100.0, within(0.01)); // rank 5 of 5 = 100%
        }

        @Test
        @DisplayName("거래금액 내림차순으로 순위 결정 — 높은 금액이 낮은 백분위(상위)")
        void calculate_rankByDescendingAmount_highAmountGetsLowPercentile() {
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry("LOW", 100.0),
                            new AdtvPercentileCalculator.RankEntry("HIGH", 10000.0),
                            new AdtvPercentileCalculator.RankEntry("MID", 5000.0));

            Map<String, Double> result = calculator.calculate(entries);

            // HIGH (10000) → rank 1, 백분위 가장 낮음
            assertThat(result.get("HIGH")).isLessThan(result.get("MID"));
            assertThat(result.get("MID")).isLessThan(result.get("LOW"));
        }

        @Test
        @DisplayName("이미 순위 순서로 전달된 경우도 정확히 계산")
        void calculate_alreadySorted_correctPercentiles() {
            // KIS API는 이미 순위 순서로 반환하지만 calculator는 자체적으로 정렬해야 함
            List<AdtvPercentileCalculator.RankEntry> entries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry("RANK1", 3000.0),
                            new AdtvPercentileCalculator.RankEntry("RANK2", 2000.0),
                            new AdtvPercentileCalculator.RankEntry("RANK3", 1000.0));

            Map<String, Double> result = calculator.calculate(entries);

            // rank/total * 100: 1/3*100≈33.3, 2/3*100≈66.6, 3/3*100=100
            assertThat(result.get("RANK1")).isCloseTo(33.33, within(0.01));
            assertThat(result.get("RANK2")).isCloseTo(66.67, within(0.01));
            assertThat(result.get("RANK3")).isCloseTo(100.0, within(0.01));
        }
    }
}
