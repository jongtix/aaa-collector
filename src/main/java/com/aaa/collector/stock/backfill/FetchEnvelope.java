package com.aaa.collector.stock.backfill;

/**
 * 비트랜잭션 fetch 단계가 트랜잭션 persist 단계로 넘기는 봉투 (SPEC-COLLECTOR-BACKFILL-010 §4.1).
 *
 * <p>{@link BackfillWindowExecutor#fetchWindow}가 서비스별 fetch DTO({@code serviceFetch})와 함께 GROUP_A
 * {@code daily_ohlcv} 종료 확인 프로브 판정({@code probeOutcome})을 담아 반환한다. 확인 프로브 HTTP 호출은 fetch 단계(비tx)에서만
 * 발생하고, persist(@Transactional)는 이 봉투의 {@code probeOutcome}만 소비해 DB 쓰기 전용을 유지한다(REQ-TXB-020 보존).
 *
 * @param serviceFetch 서비스별 fetch DTO({@code DomesticDailyOhlcvFetch}·{@code
 *     OverseasDailyOhlcvFetch}·GROUP_B DTO 등). 프로브 오류(DEFERRED) 시에도 통상 윈도우 DTO는 그대로 실린다. FAILED 종목
 *     조기 단락 시 {@code null}일 수 있다.
 * @param probeOutcome 종료 확인 프로브 판정 — GROUP_B·corporate_events*·{@code rawRowCount≥100}은 {@link
 *     ProbeOutcome#NOT_APPLICABLE}
 * @param probeError 프로브 API 오류 메시지({@link ProbeOutcome#DEFERRED}일 때만 non-null)
 * @param retryable 프로브 오류의 재시도 가능 여부(REQ-BACKFILL-030 분류, DEFERRED일 때만 유효)
 */
public record FetchEnvelope(
        Object serviceFetch, ProbeOutcome probeOutcome, String probeError, boolean retryable) {

    /**
     * 게이트 비대상 봉투(GROUP_B·corporate_events*·{@code rawRowCount≥100}·FAILED 조기 단락).
     *
     * @param serviceFetch 서비스별 fetch DTO (null 허용)
     * @return NOT_APPLICABLE 봉투
     */
    public static FetchEnvelope notApplicable(Object serviceFetch) {
        return new FetchEnvelope(serviceFetch, ProbeOutcome.NOT_APPLICABLE, null, false);
    }

    /**
     * 프로브 판정이 확정된 봉투(DEFERRED 제외).
     *
     * @param serviceFetch 서비스별 fetch DTO
     * @param probeOutcome 확정된 프로브 판정
     * @return 판정 봉투
     */
    public static FetchEnvelope of(Object serviceFetch, ProbeOutcome probeOutcome) {
        return new FetchEnvelope(serviceFetch, probeOutcome, null, false);
    }

    /**
     * 확인 프로브 API 오류 봉투(REQ-149).
     *
     * @param serviceFetch 통상 윈도우 fetch DTO
     * @param probeError 오류 메시지
     * @param retryable 재시도 가능 여부
     * @return DEFERRED 봉투
     */
    public static FetchEnvelope deferred(
            Object serviceFetch, String probeError, boolean retryable) {
        return new FetchEnvelope(serviceFetch, ProbeOutcome.DEFERRED, probeError, retryable);
    }
}
