package com.aaa.collector.stock;

/**
 * 국내 액면교체(SPLIT) 종목지정 백필 fetch가 100행 캡에 도달했을 때 던지는 비재시도(terminal) 예외
 * (SPEC-COLLECTOR-BACKFILL-GROUPC-001 REQ-GC-013/014/031).
 *
 * <p>국내 액면교체 종목지정 조회({@code HHKDB669105C0})는 CTS 기반 연속조회가 구조적으로 불가하다(§3 Exclusions). 원본 응답 행수가
 * {@code MAX_ROWS_PER_PAGE}(100)에 도달하면 전 범위를 확보하지 못했을 가능성(절단 의심)이 있으므로, {@link
 * com.aaa.collector.stock.backfill.BackfillTerminationPolicy}가 이를 조용히 COMPLETED로 오판하지 않도록 재시도 없이
 * terminal FAILED로 종단시키는 안전밸브다(REQ-GC-031). 실무상 종목당 이벤트가 극소수(§21 실측 최댓값 1건)라 도달 불가능에 가깝다.
 *
 * <p>{@link com.aaa.collector.stock.backfill.BackfillWindowExecutor#isRetryable(Exception)}이 이 예외를
 * 비재시도(false)로 분류한다(REQ-GC-014) — 기본값(재시도 가능)에 맡기면 IN_PROGRESS 무한 재시도가 재현된다.
 */
public class RevSplitBackfillCapSaturatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RevSplitBackfillCapSaturatedException(String message) {
        super(message);
    }
}
