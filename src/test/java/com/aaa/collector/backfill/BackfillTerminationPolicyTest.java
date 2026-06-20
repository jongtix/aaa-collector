package com.aaa.collector.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 백필 종료 판정기 명세 (SPEC-COLLECTOR-BACKFILL-001 T5, AC-1/AC-2).
 *
 * <p>순수 로직 — KIS/Spring 비의존. 임계값(기본 N=3)은 생성자 주입.
 */
class BackfillTerminationPolicyTest {

    private static final int STALE_THRESHOLD = 3;
    private final BackfillTerminationPolicy policy = new BackfillTerminationPolicy(STALE_THRESHOLD);

    @Nested
    @DisplayName("그룹 A — daily_ohlcv (국내·해외)")
    class GroupADailyOhlcv {

        @Test
        @DisplayName("AC-1.1: 0건 윈도우면 COMPLETED")
        void zeroRows_completed() {
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupA(0, null);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
        }

        @Test
        @DisplayName("AC-1.2: 100건 미만(37건)이면 상장 초기 도달로 COMPLETED")
        void belowCap_completed() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupA(37, LocalDate.of(2015, 3, 2));

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
        }

        @Test
        @DisplayName("AC-1.3: 정확히 100건이면 IN_PROGRESS 유지(종료 아님)")
        void exactlyCap_inProgress() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupA(100, LocalDate.of(2018, 1, 5));

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isFalse();
        }
    }

    @Nested
    @DisplayName("그룹 A SPAN — AC-1.2b 오종료 방지(음성 검증)")
    class GroupASpanGuard {

        @Test
        @DisplayName("AC-1.2b: 100건-미만 종료 규칙은 행수만 보고 판정 — SPAN 보장은 Advancer 책임이며 여기서 67건도 종료로 처리됨")
        void belowCapRule_isRowCountOnly() {
            // SPAN 부족(100달력일≈67거래일)으로 67건이 나오면 종료로 오판된다.
            // 따라서 종료 판정기는 행수만 본다 — SPAN ≥150 달력일 보장은
            // BackfillWindowAdvancer가 책임지며 그 가드는 별도 테스트가 검증한다.
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupA(67, LocalDate.of(2016, 6, 1));

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
        }
    }

    @Nested
    @DisplayName("그룹 B — 진행 정체 종료 (공매도·investor·credit)")
    class GroupBStaleProgress {

        @Test
        @DisplayName("AC-2.1: 연속 3회 무전진이면 COMPLETED")
        void threeConsecutiveNoProgress_completed() {
            LocalDate oldest = LocalDate.of(2019, 1, 4);
            // 무전진 카운터 2 상태에서 또 무전진 → 3 도달 → 종료
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(30, oldest, oldest, 30, 2);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
            assertThat(decision.nextStaleCount()).isEqualTo(STALE_THRESHOLD);
        }

        @Test
        @DisplayName("AC-2.1b: 무전진 2회(<N=3)는 종료 아님 — IN_PROGRESS")
        void twoNoProgress_inProgress() {
            LocalDate oldest = LocalDate.of(2019, 1, 4);
            // 무전진 카운터 1 상태에서 또 무전진 → 2 → 아직 종료 아님
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(30, oldest, oldest, 30, 1);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isFalse();
            assertThat(decision.nextStaleCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-2.1c: 과거로 전진하면 무전진 카운터 0 리셋 + IN_PROGRESS")
        void advance_resetsCounter() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupB(
                            30, LocalDate.of(2018, 12, 20), LocalDate.of(2019, 1, 4), 30, 2);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isFalse();
            assertThat(decision.nextStaleCount()).isZero();
        }

        @Test
        @DisplayName("AC-2.2: 0건이면 N 임계와 무관하게 즉시 COMPLETED")
        void zeroRows_immediateCompleted() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupB(0, null, LocalDate.of(2019, 1, 4), 30, 0);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
        }
    }

    @Nested
    @DisplayName("그룹 B — 100건-미만 종료 규칙 미적용 (음성 검증)")
    class GroupBNoBelowCapRule {

        @Test
        @DisplayName("AC-2.3: investor_trend 정상 ~30건(<100)+전진이면 IN_PROGRESS")
        void investorTrendNormalWindow_inProgress() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupB(
                            30, LocalDate.of(2018, 12, 20), LocalDate.of(2019, 1, 4), 30, 0);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isFalse();
        }

        @Test
        @DisplayName("AC-2.4: 공매도 정상 61건(<100)+전진이면 IN_PROGRESS — CR-02 회귀 가드")
        void shortSaleNormalWindow_inProgress() {
            // short_sale_domestic은 그룹 B이므로 100건-미만 종료 규칙을 적용하지 않는다.
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupB(
                            61, LocalDate.of(2018, 12, 20), LocalDate.of(2019, 1, 4), 61, 0);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isFalse();
        }
    }

    @Nested
    @DisplayName("그룹 B — 클램프 의심 종료 (AC-2.5, REQ-014a)")
    class GroupBClampSuspicion {

        @Test
        @DisplayName("AC-2.5: 동일 oldest + 동일 행수로 3회 무전진이면 COMPLETED + 클램프 플래그")
        void identicalOldestAndRowCount_clampSuspected() {
            LocalDate oldest = LocalDate.of(2019, 1, 4);
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(30, oldest, oldest, 30, 2);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
            assertThat(decision.clampSuspected()).isTrue();
        }

        @Test
        @DisplayName("AC-2.5 음성: 행수가 다르면(무전진이어도) 클램프 의심 아님")
        void differentRowCount_notClampSuspected() {
            LocalDate oldest = LocalDate.of(2019, 1, 4);
            // oldest 동일하지만 행수 다름 → 무전진 종료이되 클램프는 아님
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(28, oldest, oldest, 30, 2);

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
            assertThat(decision.clampSuspected()).isFalse();
        }
    }

    @Nested
    @DisplayName("data_table → 그룹 분류 (CR-02 회귀 가드)")
    class GroupClassification {

        @Test
        @DisplayName("daily_ohlcv만 그룹 A")
        void dailyOhlcv_isGroupA() {
            assertThat(BackfillGroup.ofDataTable("daily_ohlcv")).isEqualTo(BackfillGroup.GROUP_A);
        }

        @Test
        @DisplayName("CR-02: short_sale_domestic은 그룹 B (100건-미만 종료 규칙 제외)")
        void shortSaleDomestic_isGroupB() {
            assertThat(BackfillGroup.ofDataTable("short_sale_domestic"))
                    .isEqualTo(BackfillGroup.GROUP_B);
        }

        @Test
        @DisplayName("investor_trend·credit_balance도 그룹 B")
        void supplyTables_areGroupB() {
            assertThat(BackfillGroup.ofDataTable("investor_trend"))
                    .isEqualTo(BackfillGroup.GROUP_B);
            assertThat(BackfillGroup.ofDataTable("credit_balance"))
                    .isEqualTo(BackfillGroup.GROUP_B);
        }
    }

    @Nested
    @DisplayName("무전진 카운터 경계 (2=계속, 3=종료, 전진=리셋)")
    class StaleCounterBoundary {

        @Test
        @DisplayName("경계: staleCount 2 → 또 무전진 → 3 종료")
        void boundaryThree_terminates() {
            LocalDate oldest = LocalDate.of(2020, 5, 1);
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(10, oldest, oldest, 10, 2);

            assertThat(policy.decide(outcome).completed()).isTrue();
        }

        @Test
        @DisplayName("경계: staleCount 1 → 또 무전진 → 2 계속")
        void boundaryTwo_continues() {
            LocalDate oldest = LocalDate.of(2020, 5, 1);
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupB(10, oldest, oldest, 10, 1);

            assertThat(policy.decide(outcome).completed()).isFalse();
        }
    }
}
