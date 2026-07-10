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
    @DisplayName("그룹 A — rawRowCount 종료 판정 (SPEC-COLLECTOR-BACKFILL-006 — AC-5/AC-7/AC-13)")
    class GroupARawRowCountTermination {

        @Test
        @DisplayName(
                "AC-5/AC-13: rawRowCount=100(저장 97/99 무관) → IN_PROGRESS — 거래정지·무효 거부가 종료를 흔들지 않음")
        void rawRowCount100_inProgress_regardlessOfRowCount() {
            // 저장 행수 97(거래정지 3 저장) — 구 동작이면 97<100으로 오종료. rawRowCount=100이면 계속.
            BackfillWindowOutcome haltCase =
                    BackfillWindowOutcome.groupA(97, 100, LocalDate.of(2018, 4, 30));
            // 저장 행수 99(진짜 오류 1건 거부) — Axis 2 독립(§5.0)
            BackfillWindowOutcome invalidCase =
                    BackfillWindowOutcome.groupA(99, 100, LocalDate.of(2017, 12, 31));

            assertThat(policy.decide(haltCase).completed()).isFalse();
            assertThat(policy.decide(invalidCase).completed()).isFalse();
        }

        @Test
        @DisplayName("AC-7: rawRowCount=99(KIS 데이터 소진 근접) → COMPLETED (자연 종료)")
        void rawRowCount99_completed() {
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupA(99, 99, LocalDate.of(1995, 3, 2));

            assertThat(policy.decide(outcome).completed()).isTrue();
        }

        @Test
        @DisplayName(
                "AC-7: 종료 입력은 rawRowCount이며 rowCount는 무시 — rowCount=100이어도 rawRowCount=99면 COMPLETED")
        void terminationUsesRawRowCount_notRowCount() {
            // rowCount=100(저장)이지만 rawRowCount=99 — 종료 입력은 rawRowCount이므로 COMPLETED
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupA(100, 99, LocalDate.of(1995, 3, 2));

            assertThat(policy.decide(outcome).completed()).isTrue();
        }

        @Test
        @DisplayName("회귀: 임계값 100 불변 — rawRowCount=100은 IN_PROGRESS, 99는 COMPLETED 경계")
        void thresholdBoundary_unchanged() {
            assertThat(
                            policy.decide(
                                            BackfillWindowOutcome.groupA(
                                                    100, 100, LocalDate.of(2018, 1, 5)))
                                    .completed())
                    .isFalse();
            assertThat(
                            policy.decide(
                                            BackfillWindowOutcome.groupA(
                                                    99, 99, LocalDate.of(2018, 1, 5)))
                                    .completed())
                    .isTrue();
        }

        @Test
        @DisplayName("AC-14/EC-8: 거래정지 100건 경계 윈도우 체이닝 — A·B 모두 IN_PROGRESS, 소진 윈도우만 COMPLETED")
        void windowChaining_haltAcrossBoundary_continuesUntilExhaustion() {
            // 윈도우 A: 원본 100건(끝부분 거래정지 2 → 저장 98 가정) → rawRowCount=100 → IN_PROGRESS
            BackfillWindowOutcome windowA =
                    BackfillWindowOutcome.groupA(98, 100, LocalDate.of(2018, 1, 5));
            // 윈도우 B: 원본 100건(시작부분 거래정지 2 → 저장 98) → rawRowCount=100 → IN_PROGRESS
            BackfillWindowOutcome windowB =
                    BackfillWindowOutcome.groupA(98, 100, LocalDate.of(2017, 8, 10));
            // 데이터 소진 윈도우: 원본 40건 → COMPLETED
            BackfillWindowOutcome exhaustion =
                    BackfillWindowOutcome.groupA(40, 40, LocalDate.of(1995, 6, 1));

            assertThat(policy.decide(windowA).completed()).isFalse();
            assertThat(policy.decide(windowB).completed()).isFalse();
            assertThat(policy.decide(exhaustion).completed()).isTrue();
        }
    }

    @Nested
    @DisplayName("EC-9 연속 분할 — 다중 거래정지 클러스터 윈도우별 stateless 종료 (SPEC-COLLECTOR-BACKFILL-006)")
    class ConsecutiveSplitMultipleHaltClusters {

        /**
         * EC-9: 두 번 이상의 액면분할이 연속으로 발생해 다수의 윈도우 각각에 거래정지 행이 포함되는 경우.
         *
         * <p>종료 판정기는 윈도우별 stateless이므로, 이전 윈도우의 거래정지 존재 여부와 무관하게 각 윈도우의 rawRowCount만으로 독립 판정한다.
         * rawRowCount=100인 윈도우는 몇 개가 연속으로 오더라도 모두 IN_PROGRESS를 반환해야 한다.
         */
        @Test
        @DisplayName("EC-9: 3개 연속 윈도우 각각 거래정지 클러스터 포함, rawRowCount=100 → 모두 IN_PROGRESS (오종료 없음)")
        void consecutiveSplits_threeWindowsEachWithHaltCluster_allInProgress() {
            // 분할 1 구간 — 거래정지 3일 저장 포함(97건 저장, 100건 원본)
            BackfillWindowOutcome split1Window =
                    BackfillWindowOutcome.groupA(97, 100, LocalDate.of(2021, 3, 31));
            // 분할 2 구간 — 거래정지 2일 저장 포함(98건 저장, 100건 원본)
            BackfillWindowOutcome split2Window =
                    BackfillWindowOutcome.groupA(98, 100, LocalDate.of(2019, 9, 27));
            // 분할 3 구간 — 거래정지 5일 저장 포함(95건 저장, 100건 원본)
            BackfillWindowOutcome split3Window =
                    BackfillWindowOutcome.groupA(95, 100, LocalDate.of(2017, 4, 28));

            // 각 윈도우가 독립적으로 IN_PROGRESS 반환 — 연속 거래정지가 종료를 흔들지 않음
            assertThat(policy.decide(split1Window).completed()).isFalse();
            assertThat(policy.decide(split2Window).completed()).isFalse();
            assertThat(policy.decide(split3Window).completed()).isFalse();
        }

        @Test
        @DisplayName("EC-9: 다중 클러스터 체인 후 데이터 소진(rawRowCount=40) 시에만 COMPLETED")
        void consecutiveSplits_terminatesOnlyAtExhaustion() {
            // 3개 할트 클러스터 윈도우 — 모두 rawRowCount=100이므로 IN_PROGRESS
            BackfillWindowOutcome cluster1 =
                    BackfillWindowOutcome.groupA(97, 100, LocalDate.of(2021, 3, 31));
            BackfillWindowOutcome cluster2 =
                    BackfillWindowOutcome.groupA(96, 100, LocalDate.of(2019, 9, 27));
            BackfillWindowOutcome cluster3 =
                    BackfillWindowOutcome.groupA(98, 100, LocalDate.of(2017, 4, 28));
            // 정상 데이터 소진 윈도우 — rawRowCount=40 → COMPLETED
            BackfillWindowOutcome exhaustion =
                    BackfillWindowOutcome.groupA(40, 40, LocalDate.of(1996, 1, 15));

            assertThat(policy.decide(cluster1).completed()).isFalse();
            assertThat(policy.decide(cluster2).completed()).isFalse();
            assertThat(policy.decide(cluster3).completed()).isFalse();
            assertThat(policy.decide(exhaustion).completed()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC-9 회귀 가드 — 임계값·GROUP_B 불변 (SPEC-COLLECTOR-BACKFILL-006)")
    class TerminationRegressionGuard {

        @Test
        @DisplayName(
                "AC-9: GROUP_B는 rawRowCount=rowCount이므로 100건-미만 종료 규칙 비적용 유지 (61건+전진 IN_PROGRESS)")
        void groupB_rawRowCountEqualsRowCount_noBelowCapRule() {
            // groupB factory가 rawRowCount=rowCount로 설정 — decideGroupB는 rowCount 경로이므로 100-cap 무관
            BackfillWindowOutcome outcome =
                    BackfillWindowOutcome.groupB(
                            61, LocalDate.of(2018, 12, 20), LocalDate.of(2019, 1, 4), 61, 0);

            assertThat(policy.decide(outcome).completed()).isFalse();
            // rawRowCount가 rowCount와 동일함을 명시 고정
            assertThat(outcome.rawRowCount()).isEqualTo(outcome.rowCount());
        }

        @Test
        @DisplayName("AC-9: GROUP_B 0건 종료·clamp 판정은 rowCount 경로로 불변")
        void groupB_zeroAndClamp_unchanged() {
            BackfillWindowOutcome zero =
                    BackfillWindowOutcome.groupB(0, null, LocalDate.of(2019, 1, 4), 30, 0);
            assertThat(policy.decide(zero).completed()).isTrue();

            LocalDate oldest = LocalDate.of(2019, 1, 4);
            BackfillWindowOutcome clamp = BackfillWindowOutcome.groupB(30, oldest, oldest, 30, 2);
            assertThat(policy.decide(clamp).clampSuspected()).isTrue();
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

        @Test
        @DisplayName(
                "REQ-GC-002 (구 AC-1): corporate_events는 그룹 C로 이관됨 — GROUP_A 100건-미만 규칙 더 이상 미적용")
        void corporateEvents_isGroupC() {
            assertThat(BackfillGroup.ofDataTable("corporate_events"))
                    .isEqualTo(BackfillGroup.GROUP_C);
        }
    }

    @Nested
    @DisplayName(
            "그룹 C — corporate_events 커서완주·단일콜 소진 (AC-2, SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-003/030)")
    class GroupCUnconditionalCompletion {

        @Test
        @DisplayName("AC-2: GROUP_C outcome은 행수(0/50/100/108) 무관 전부 completed(0,false) 반환")
        void groupC_alwaysCompleted_regardlessOfRowCount() {
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupC();

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
            assertThat(decision.nextStaleCount()).isZero();
            assertThat(decision.clampSuspected()).isFalse();
        }

        @Test
        @DisplayName("REQ-GC-003: GROUP_C는 decideGroupB(클램프·stale 로직) 경로로 오염되지 않는다")
        void groupC_doesNotGoThroughDecideGroupB() {
            // decideGroupB였다면 previousOldest=null이므로 advanced()=true → inProgress(0)이 나와야 하지만,
            // GROUP_C는 무조건 completed다 — 두 경로가 다르다는 것을 행위로 검증.
            BackfillWindowOutcome outcome = BackfillWindowOutcome.groupC();

            TerminationDecision decision = policy.decide(outcome);

            assertThat(decision.completed()).isTrue();
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
