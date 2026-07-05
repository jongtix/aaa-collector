package com.aaa.collector.stock.rights;

/**
 * 해외 SPLIT 백필 fetch 단계에서 CTRGT011R 프리페치 결과가 유형(14/15) 단위로 FAILED 또는 TRUNCATED일 때 던지는 예외
 * (SPEC-COLLECTOR-OVERSEAS-SPLIT-001, 결함 수정).
 *
 * <p>정기 수집({@link OverseasSplitCollectionService#collect()})은 유형 단위 실패/절단을 fail-closed로 폐기하고 다음
 * 배치(cron)로 미룬다(REQ-OSPLIT-070/071, 변경 없음). 그러나 백필은 1회성 윈도우이므로 같은 방식으로 폐기하면 {@code rawRowCount}가
 * 실제보다 낮게 조작되어 {@link com.aaa.collector.backfill.BackfillTerminationPolicy}가 슬롯을 영구 {@code
 * COMPLETED}로 오판, 재시도 기회 없이 SPLIT 이벤트가 유실될 수 있다.
 *
 * <p>이 예외는 {@link OverseasSplitCollectionService#fetchWindowForBackfill}에서 던져져 {@link
 * com.aaa.collector.stock.backfill.BackfillWindowExecutor#fetchWindow}를 거쳐 전파되고, {@code
 * BackfillOrchestrator.executeOneWindow}의 범용 예외 처리기가 재시도 가능(retryable=true)으로 분류해 슬롯을 {@code
 * IN_PROGRESS}로 유지한다 — 국내 {@link com.aaa.collector.stock.RevSplitCollectionService}의 예외 전파 계약과
 * 동등하다.
 */
class OverseasSplitBackfillPrefetchFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    OverseasSplitBackfillPrefetchFailedException(String message) {
        super(message);
    }
}
