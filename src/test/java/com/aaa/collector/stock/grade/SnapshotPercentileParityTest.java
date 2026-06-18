package com.aaa.collector.stock.grade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aaa.collector.stock.grade.snapshot.RankingSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 스냅샷 저장→읽기→RankEntry 재구성 후 percentile 재계산 정합성 검증.
 *
 * <p>SPEC-COLLECTOR-GRADE-003 v2.0.0 회귀 위험(plan.md): GradeClassificationService가 스냅샷에서 RankEntry를
 * 재구성하는 경로(s.getSymbol(), s.getRankValue())가 원본 RankEntry 목록에 대해 AdtvPercentileCalculator를 직접 호출한
 * 결과와 동일한 percentile을 산출해야 한다.
 *
 * <p>검증 방법:
 *
 * <ol>
 *   <li>원본 {@code List<RankEntry>} → {@code AdtvPercentileCalculator.calculate()} → expected
 *   <li>동일 RankEntry로 {@link RankingSnapshot#of(String, LocalDate, String, double, int, Instant)}
 *       생성(saveSnapshot 변환 재현)
 *   <li>스냅샷 목록에서 GradeClassificationService와 동일한 방식으로 RankEntry 재구성
 *   <li>{@code AdtvPercentileCalculator.calculate()} 재호출 → actual
 *   <li>expected == actual (종목별)
 * </ol>
 */
@DisplayName("스냅샷 round-trip percentile 재계산 정합성")
class SnapshotPercentileParityTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2024, 6, 17);
    private static final Instant CAPTURED_AT = Instant.parse("2024-06-16T19:00:00Z");
    private static final String MARKET = "KRX";

    private final AdtvPercentileCalculator calculator = new AdtvPercentileCalculator();

    /**
     * RankEntry 목록 → RankingSnapshot 변환.
     *
     * <p>RankingSnapshotService.saveSnapshot() 루프와 동일한 로직: rank_value = e.rankValue(),
     * rank_position = i + 1
     */
    private List<RankingSnapshot> toSnapshots(List<AdtvPercentileCalculator.RankEntry> entries) {
        List<RankingSnapshot> snapshots = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            AdtvPercentileCalculator.RankEntry e = entries.get(i);
            snapshots.add(
                    RankingSnapshot.of(
                            MARKET, SNAPSHOT_DATE, e.symbol(), e.rankValue(), i + 1, CAPTURED_AT));
        }
        return snapshots;
    }

    /**
     * RankingSnapshot 목록 → RankEntry 재구성.
     *
     * <p>GradeClassificationService.classifyDomestic()/classifyOverseas() stream과 동일한 로직: new
     * RankEntry(s.getSymbol(), s.getRankValue())
     */
    private List<AdtvPercentileCalculator.RankEntry> toRankEntries(
            List<RankingSnapshot> snapshots) {
        return snapshots.stream()
                .map(s -> new AdtvPercentileCalculator.RankEntry(s.getSymbol(), s.getRankValue()))
                .toList();
    }

    @Nested
    @DisplayName("스냅샷 round-trip — percentile 동일 보장")
    class RoundTripParity {

        @Test
        @DisplayName("5개 종목: 원본 RankEntry percentile == 스냅샷 재구성 RankEntry percentile")
        void snapshotRoundTrip_fiveSymbols_percentileParity() {
            // Arrange
            List<AdtvPercentileCalculator.RankEntry> rawEntries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry(
                                    "005930", 5_000_000.0), // 삼성전자 — 1위
                            new AdtvPercentileCalculator.RankEntry(
                                    "000660", 4_000_000.0), // SK하이닉스 — 2위
                            new AdtvPercentileCalculator.RankEntry(
                                    "035420", 3_000_000.0), // NAVER — 3위
                            new AdtvPercentileCalculator.RankEntry(
                                    "005380", 2_000_000.0), // 현대차 — 4위
                            new AdtvPercentileCalculator.RankEntry(
                                    "051910", 1_000_000.0) // LG화학 — 5위
                            );

            // Act
            Map<String, Double> expected = calculator.calculate(rawEntries);

            List<RankingSnapshot> snapshots = toSnapshots(rawEntries);
            List<AdtvPercentileCalculator.RankEntry> reconstructed = toRankEntries(snapshots);
            Map<String, Double> actual = calculator.calculate(reconstructed);

            // Assert
            assertThat(actual).hasSameSizeAs(expected);
            expected.forEach(
                    (symbol, expectedPct) ->
                            assertThat(actual.get(symbol))
                                    .as("symbol=%s 백분위", symbol)
                                    .isCloseTo(expectedPct, within(0.001)));
        }

        @Test
        @DisplayName("rankValue가 역순(입력 비정렬) — 스냅샷 재구성 후도 동일 percentile 순서 보장")
        void snapshotRoundTrip_unsortedInput_orderPreservedInPercentile() {
            // Arrange — rankValue가 순위와 반대 순서로 입력 (calculator가 자체 정렬해야 함을 검증)
            List<AdtvPercentileCalculator.RankEntry> rawEntries =
                    List.of(
                            new AdtvPercentileCalculator.RankEntry("LOW", 100.0),
                            new AdtvPercentileCalculator.RankEntry("HIGH", 9_000.0),
                            new AdtvPercentileCalculator.RankEntry("MID", 5_000.0));

            // Act
            Map<String, Double> expected = calculator.calculate(rawEntries);

            List<RankingSnapshot> snapshots = toSnapshots(rawEntries);
            List<AdtvPercentileCalculator.RankEntry> reconstructed = toRankEntries(snapshots);
            Map<String, Double> actual = calculator.calculate(reconstructed);

            // Assert: 종목별 percentile 일치 + HIGH가 가장 낮은 백분위(상위)
            assertThat(actual.get("HIGH")).isCloseTo(expected.get("HIGH"), within(0.001));
            assertThat(actual.get("MID")).isCloseTo(expected.get("MID"), within(0.001));
            assertThat(actual.get("LOW")).isCloseTo(expected.get("LOW"), within(0.001));
            assertThat(actual.get("HIGH")).isLessThan(actual.get("MID"));
            assertThat(actual.get("MID")).isLessThan(actual.get("LOW"));
        }
    }
}
