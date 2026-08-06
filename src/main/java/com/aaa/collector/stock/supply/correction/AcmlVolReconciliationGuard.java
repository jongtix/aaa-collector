package com.aaa.collector.stock.supply.correction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * {@code acml_vol} 채움 3분기 판정 가드 (SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-050~057).
 *
 * <p>I/O 없는 순수 판정 로직 — TR04 라이브 재조회 결과(재조회 {@code acml_vol}·재조회 qty)와 기존 저장값(저장 rate·저장 qty)을 비교해
 * {@link AcmlVolReconciliationOutcome#MATCHED}/{@link
 * AcmlVolReconciliationOutcome#EVENT_ADJUSTED}/{@link
 * AcmlVolReconciliationOutcome#REVISION_SUSPECTED} 중 하나로 판정한다. 판정 순서는 plan.md §M3 원문을 그대로 따른다 —
 * 나눗셈-0 전제조건이 항상 먼저다.
 *
 * <p>경계값 {@code ratio ≤ 0.5 OR ratio ≥ 2.0}의 경험적 근거: plan.md §M3 "D2 재감사 대응" 참조 — 실제 T+0 리비전 사례(비율
 * 0.607~1.016)와 실제 분할·병합 사례(비율 0.15~0.50, 2.50~10.3) 사이에 {@code (0.5, 2.0)} 전 구간이 완전히 비어 있다. 이 위험은
 * 비대칭적이다(plan.md §6 R3) — {@code (0.5,2.0)} 내부 오분류는 안전측(정정 스킵)이나, 그 밖 오분류는 {@code
 * vol_rate_verified_at} 기록으로 영구 재검증 불가가 된다.
 *
 * <p>이 가드는 Track 1(레거시·{@code acml_vol} 결측 가드) 전용이다 — Track 2(신규 삽입 행 상시 스윕)에서는 호출되지
 * 않는다(REQ-SSVC-057).
 */
// @MX:ANCHOR: [AUTO] acml_vol 3분기 판정 — Track 1 정정 서비스(M4)·레거시 백필(M6)의 유일한 판정 지점
// @MX:REASON: SPEC-COLLECTOR-SHORTSALE-VOLRATE-CORRECTION-001 REQ-SSVC-050~057, fan_in>=3 예상
// (Track 1 서비스·M6 백필 루프·단위 테스트)
@Component
public class AcmlVolReconciliationGuard {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal RATIO_LOWER_BOUND = new BigDecimal("0.5");
    private static final BigDecimal RATIO_UPPER_BOUND = new BigDecimal("2.0");

    /** liveRate 산출 스케일 — 사전조사 보고서 §3.5 프로브 확증(KIS round-half-up, 소수점 2자리). */
    private static final int LIVE_RATE_SCALE = 2;

    /** ratio(저장rate÷liveRate) 산출 스케일 — 경계값(0.5/2.0) 정확한 비교를 위한 충분한 정밀도. */
    private static final int RATIO_SCALE = 10;

    /** 역산 acmlVol(정수, 주식 수)의 반올림 스케일. */
    private static final int RECONCILED_ACML_VOL_SCALE = 0;

    /**
     * 3분기 판정을 실행한다.
     *
     * @param storedRate 기존 저장된 {@code short_sell_vol_rate}
     * @param storedQty 기존 저장된 {@code short_sell_qty}
     * @param liveAcmlVol TR04 라이브 재조회 {@code acml_vol}
     * @param liveQty TR04 라이브 재조회 공매도 체결 수량
     * @return 판정 결과(outcome + 채택 대상 acmlVol)
     */
    public AcmlVolReconciliationResult reconcile(
            BigDecimal storedRate, long storedQty, long liveAcmlVol, long liveQty) {
        // 1. 재조회 acml_vol == 0 (거래정지일 응답) — 배율 계산 자체를 시도하지 않는다(REQ-SSVC-055).
        if (liveAcmlVol == 0) {
            return AcmlVolReconciliationResult.revisionSuspected();
        }

        // 2. liveRate = round(재조회qty / 재조회acmlVol × 100, 2, HALF_UP)
        BigDecimal liveRate =
                BigDecimal.valueOf(liveQty)
                        .multiply(HUNDRED)
                        .divide(
                                BigDecimal.valueOf(liveAcmlVol),
                                LIVE_RATE_SCALE,
                                RoundingMode.HALF_UP);

        // 3. liveRate == 저장rate → MATCHED, 재조회 acmlVol 그대로 채택(REQ-SSVC-051).
        if (liveRate.compareTo(storedRate) == 0) {
            return AcmlVolReconciliationResult.matched(liveAcmlVol);
        }

        // liveRate == 0인데 저장rate != 0인 경계 — ratio(저장rate÷liveRate)의 분모가 0이라 배율 계산 자체가
        // 불가하다. "나눗셈-0 전제조건이 항상 먼저"라는 원칙(plan.md §M3)을 이 분모에도 동일하게 적용해
        // REVISION_SUSPECTED로 안전측 처리한다(정정을 스킵할 뿐, 다음 실행에서 자동 재시도됨 — REQ-SSVC-053).
        if (liveRate.compareTo(BigDecimal.ZERO) == 0) {
            return AcmlVolReconciliationResult.revisionSuspected();
        }

        // 4. ratio = 저장rate / liveRate
        BigDecimal ratio = storedRate.divide(liveRate, RATIO_SCALE, RoundingMode.HALF_UP);

        // 5. ratio ≤ 0.5 OR ratio ≥ 2.0
        if (ratio.compareTo(RATIO_LOWER_BOUND) <= 0 || ratio.compareTo(RATIO_UPPER_BOUND) >= 0) {
            // 저장rate == 0.00 — 역산 분모가 0이라 역산을 시도하지 않는다(REQ-SSVC-056).
            if (storedRate.compareTo(BigDecimal.ZERO) == 0) {
                return AcmlVolReconciliationResult.revisionSuspected();
            }
            // EVENT_ADJUSTED — acmlVol = 저장qty / 저장rate × 100 역산 채택(REQ-SSVC-052).
            long reconciledAcmlVol =
                    BigDecimal.valueOf(storedQty)
                            .multiply(HUNDRED)
                            .divide(storedRate, RECONCILED_ACML_VOL_SCALE, RoundingMode.HALF_UP)
                            .longValueExact();
            return AcmlVolReconciliationResult.eventAdjusted(reconciledAcmlVol);
        }

        // 6. 0.5 < ratio < 2.0 → REVISION_SUSPECTED, 정정 스킵(REQ-SSVC-053) —
        // acml_vol·vol_rate_verified_at
        // 미충전, 다음 실행에서 Track 1 재조회 대상에 자동 재포함(REQ-T0R-041, M5 워크리스트 인계는 별도 마일스톤 범위).
        return AcmlVolReconciliationResult.revisionSuspected();
    }
}
