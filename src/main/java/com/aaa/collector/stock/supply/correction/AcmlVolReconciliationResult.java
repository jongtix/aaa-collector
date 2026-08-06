package com.aaa.collector.stock.supply.correction;

/**
 * {@link AcmlVolReconciliationGuard} 판정 결과 — 채택할 {@code outcome}과, {@link
 * AcmlVolReconciliationOutcome#MATCHED}/{@link AcmlVolReconciliationOutcome#EVENT_ADJUSTED}일 때만 값을
 * 갖는 {@code acmlVol}(채택 대상 {@code acml_vol})을 함께 반환한다.
 *
 * <p>{@link AcmlVolReconciliationOutcome#REVISION_SUSPECTED}는 정정을 스킵하므로 {@code acmlVol}은 항상 {@code
 * null}이다(REQ-SSVC-053).
 */
public record AcmlVolReconciliationResult(AcmlVolReconciliationOutcome outcome, Long acmlVol) {

    /** MATCHED — 재조회 acml_vol을 그대로 채택(REQ-SSVC-051). */
    public static AcmlVolReconciliationResult matched(long acmlVol) {
        return new AcmlVolReconciliationResult(AcmlVolReconciliationOutcome.MATCHED, acmlVol);
    }

    /** EVENT_ADJUSTED — 저장 qty/rate 역산값을 채택(REQ-SSVC-052). */
    public static AcmlVolReconciliationResult eventAdjusted(long acmlVol) {
        return new AcmlVolReconciliationResult(
                AcmlVolReconciliationOutcome.EVENT_ADJUSTED, acmlVol);
    }

    /** REVISION_SUSPECTED — 정정 스킵, 채택값 없음(REQ-SSVC-053). */
    public static AcmlVolReconciliationResult revisionSuspected() {
        return new AcmlVolReconciliationResult(
                AcmlVolReconciliationOutcome.REVISION_SUSPECTED, null);
    }
}
