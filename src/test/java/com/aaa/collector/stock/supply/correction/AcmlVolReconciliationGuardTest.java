package com.aaa.collector.stock.supply.correction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AcmlVolReconciliationGuard} 단위 테스트 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001
 * REQ-SSVC-050~057).
 *
 * <p>plan.md §M3 "D2 재감사 대응" 경험적 검증 표(005930 T+0 리비전 4건, 010120 분할·007120 병합 방향성)를 판정 케이스로 반영하고,
 * 나눗셈-0 경계(재조회 acml_vol=0, liveRate=0, 저장rate=0.00 역산분모)와 ratio 정확한 경계값(0.5·2.0)을 필수 엣지케이스로 커버한다.
 */
@DisplayName("AcmlVolReconciliationGuard 단위 테스트")
class AcmlVolReconciliationGuardTest {

    private final AcmlVolReconciliationGuard guard = new AcmlVolReconciliationGuard();

    @Nested
    @DisplayName("MATCHED — liveRate == 저장rate (REQ-SSVC-051)")
    class Matched {

        @Test
        @DisplayName("§3.5 07-24: 저장 2.73 → 확정 2.73(무변경) — 재조회 acmlVol 그대로 채택")
        void unchanged_rate_matches() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=27,300 → liveRate = 27300*100/1000000 = 2.73
            BigDecimal storedRate = new BigDecimal("2.73");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 27_300, 1_000_000, 27_300);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.MATCHED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("liveRate == 0(liveQty=0)이고 저장rate도 0.00이면 등식이 성립해 MATCHED")
        void bothZero_matches() {
            // Arrange
            BigDecimal storedRate = new BigDecimal("0.00");

            // Act
            AcmlVolReconciliationResult result = guard.reconcile(storedRate, 0, 1_000_000, 0);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.MATCHED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }
    }

    @Nested
    @DisplayName("EVENT_ADJUSTED — ratio ≤ 0.5 또는 ≥ 2.0, 저장rate != 0 (REQ-SSVC-052)")
    class EventAdjusted {

        @Test
        @DisplayName("010120 분할 방향성(ratio≈5.0, ≥2.0) — acmlVol = 저장qty/저장rate×100 역산")
        void splitDirection_ratioAboveUpperBound() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=10,000 → liveRate=1.00, storedRate=5.00
            // → ratio=5.00/1.00=5.0 (≥2.0)
            BigDecimal storedRate = new BigDecimal("5.0000");
            long storedQty = 50_000; // 역산: 50000/5.0000*100 = 1,000,000

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, storedQty, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.EVENT_ADJUSTED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("007120 병합 방향성(ratio≈0.2, ≤0.5) — acmlVol = 저장qty/저장rate×100 역산")
        void mergeDirection_ratioBelowLowerBound() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=10,000 → liveRate=1.00, storedRate=0.20
            // → ratio=0.20/1.00=0.2 (≤0.5)
            BigDecimal storedRate = new BigDecimal("0.2000");
            long storedQty = 2_000; // 역산: 2000/0.2000*100 = 1,000,000

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, storedQty, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.EVENT_ADJUSTED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("ratio 정확히 0.5 경계 — \"≤0.5\" 포함 조건이므로 EVENT_ADJUSTED")
        void ratioExactlyLowerBound_isEventAdjusted() {
            // Arrange — liveRate=1.00, storedRate=0.50 → ratio=0.5 정확히
            BigDecimal storedRate = new BigDecimal("0.50");
            long storedQty = 5_000; // 역산: 5000/0.50*100 = 1,000,000

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, storedQty, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.EVENT_ADJUSTED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }

        @Test
        @DisplayName("ratio 정확히 2.0 경계 — \"≥2.0\" 포함 조건이므로 EVENT_ADJUSTED")
        void ratioExactlyUpperBound_isEventAdjusted() {
            // Arrange — liveRate=1.00, storedRate=2.00 → ratio=2.0 정확히
            BigDecimal storedRate = new BigDecimal("2.00");
            long storedQty = 20_000; // 역산: 20000/2.00*100 = 1,000,000

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, storedQty, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.EVENT_ADJUSTED);
            assertThat(result.acmlVol()).isEqualTo(1_000_000L);
        }
    }

    @Nested
    @DisplayName("REVISION_SUSPECTED — 0.5 < ratio < 2.0, T+0 리비전 경험적 검증 (REQ-SSVC-053)")
    class RevisionSuspectedByRatio {

        @Test
        @DisplayName("§3.5 07-20: 저장 1.56 → 확정 2.57 (ratio=0.607) — REVISION_SUSPECTED")
        void case0720_ratio0607() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=25,700 → liveRate=2.57
            BigDecimal storedRate = new BigDecimal("1.56");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 15_600, 1_000_000, 25_700);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName("§3.5 07-30: 저장 4.78 → 확정 5.45 (ratio=0.877) — REVISION_SUSPECTED")
        void case0730_ratio0877() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=54,500 → liveRate=5.45
            BigDecimal storedRate = new BigDecimal("4.78");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 47_800, 1_000_000, 54_500);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName("§3.5 08-03: 저장 16.36 → 확정 16.10(분모만 리비전, ratio=1.016) — REVISION_SUSPECTED")
        void case0803_ratio1016() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=161,000 → liveRate=16.10
            BigDecimal storedRate = new BigDecimal("16.36");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 163_600, 1_000_000, 161_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName("ratio=0.51 (0.5 초과, 열린구간 하단 안쪽) — REVISION_SUSPECTED")
        void ratioJustAboveLowerBound_isRevisionSuspected() {
            // Arrange — liveRate=1.00, storedRate=0.51 → ratio=0.51
            BigDecimal storedRate = new BigDecimal("0.51");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 5_100, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName("ratio=1.99 (2.0 미만, 열린구간 상단 안쪽) — REVISION_SUSPECTED")
        void ratioJustBelowUpperBound_isRevisionSuspected() {
            // Arrange — liveRate=1.00, storedRate=1.99 → ratio=1.99
            BigDecimal storedRate = new BigDecimal("1.99");

            // Act
            AcmlVolReconciliationResult result =
                    guard.reconcile(storedRate, 19_900, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }
    }

    @Nested
    @DisplayName("REVISION_SUSPECTED — 나눗셈-0 전제조건 (REQ-SSVC-055/056)")
    class DivisionByZeroGuards {

        @Test
        @DisplayName("재조회 acml_vol == 0(거래정지일 응답) — 배율 계산 자체를 시도하지 않고 즉시 REVISION_SUSPECTED")
        void liveAcmlVolZero_shortCircuitsBeforeRatio() {
            // Act — liveQty도 임의값(0)이나 liveAcmlVol=0 자체가 즉시 분기
            AcmlVolReconciliationResult result =
                    guard.reconcile(new BigDecimal("1.56"), 15_600, 0, 0);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName(
                "liveRate == 0(liveQty=0, liveAcmlVol!=0)인데 저장rate != 0 — ratio 분모 0, 예외 없이"
                        + " REVISION_SUSPECTED")
        void liveRateZero_storedRateNonZero_noArithmeticException() {
            // Act — liveAcmlVol=1,000,000 (0 아님), liveQty=0 → liveRate=0.00, storedRate=1.56(!=0)
            AcmlVolReconciliationResult result =
                    guard.reconcile(new BigDecimal("1.56"), 15_600, 1_000_000, 0);

            // Assert — ArithmeticException 없이 안전측(정정 스킵)으로 귀결
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }

        @Test
        @DisplayName(
                "저장rate == 0.00이고 ratio가 경계 밖(≤0.5) — 역산 분모 0, EVENT_ADJUSTED 대신 REVISION_SUSPECTED")
        void storedRateZero_ratioOutOfBound_noArithmeticExceptionOnReversal() {
            // Arrange — liveAcmlVol=1,000,000, liveQty=10,000 → liveRate=1.00, storedRate=0.00
            // → ratio = 0.00/1.00 = 0 (≤0.5) 이지만 역산 분모(저장rate)가 0이라 역산을 시도하지 않는다
            BigDecimal storedRate = new BigDecimal("0.00");

            // Act
            AcmlVolReconciliationResult result = guard.reconcile(storedRate, 0, 1_000_000, 10_000);

            // Assert
            assertThat(result.outcome()).isEqualTo(AcmlVolReconciliationOutcome.REVISION_SUSPECTED);
            assertThat(result.acmlVol()).isNull();
        }
    }
}
