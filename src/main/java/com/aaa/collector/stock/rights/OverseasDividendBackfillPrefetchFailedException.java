package com.aaa.collector.stock.rights;

/**
 * 해외 현금배당 백필 fetch 단계에서 {@code rights-by-ice} 서브윈도우 청크 또는 {@code CTRGT011R} 프리페치가 실패/절단됐을 때 던지는 예외
 * (SPEC-COLLECTOR-OVERSEAS-DIVIDEND-WINDOW-001 REQ-ODW-060).
 *
 * <p>백필은 1회성 윈도우이므로 실패를 rawRowCount 조작(0건 등)으로 삼키면 재시도 기회 없이 데이터가 영구 유실된다. 이 예외는 {@link
 * OverseasRightsCollectionService#fetchWindowForBackfill}에서 던져져 {@code BackfillWindowExecutor}를 거쳐
 * 전파되고, {@code BackfillOrchestrator.executeOneWindow}의 범용 예외 처리기가 재시도 가능(retryable=true)으로 분류해 슬롯을
 * {@code IN_PROGRESS}로 유지한다 — {@link OverseasSplitBackfillPrefetchFailedException}과 동일 전파 계약.
 *
 * <p>{@code rights-by-ice}는 서브윈도우 청크 중 하나라도 실패하면(REQ-ODW-051a) 이미 성공한 다른 청크가 있어도 fetch 전체를 실패로 처리한다
 * — 부분 청크만 반영된 상태로 COMPLETED 오판되는 것을 방지한다.
 *
 * <p>코드리뷰 W-2b: {@code public}으로 공개해 {@link
 * com.aaa.collector.stock.backfill.BackfillWindowExecutor#isRetryable(Exception, int)}가 재시도 횟수 상한
 * 도달 시 이 예외를 비재시도(terminal FAILED)로 재분류할 수 있도록 한다 — 구조적으로 영구 실패하는 종목이 스케줄 백필마다 무한정 청크 조회 예산을 재소모하는
 * 것을 막는다.
 */
public class OverseasDividendBackfillPrefetchFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OverseasDividendBackfillPrefetchFailedException(String message) {
        super(message);
    }

    public OverseasDividendBackfillPrefetchFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
