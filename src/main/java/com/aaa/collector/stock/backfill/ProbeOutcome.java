package com.aaa.collector.stock.backfill;

/**
 * GROUP_A {@code daily_ohlcv} 종료 확인 프로브 판정 결과 (SPEC-COLLECTOR-BACKFILL-010 §4.1).
 *
 * <p>비트랜잭션 {@link BackfillWindowExecutor#fetchWindow}에서 산정되어 {@link FetchEnvelope}에 실려 트랜잭션 persist
 * 단계로 전달된다. persist는 이 값만 소비해 status 전이·{@code verified_at}·{@code stale_count}를 결정한다 —
 * probeOutcome은 {@code daily_ohlcv} GROUP_A에만 존재하며 GROUP_B·corporate_events*는 항상 {@link
 * #NOT_APPLICABLE}이다(§Exclusions D2/D4).
 */
public enum ProbeOutcome {

    /** 게이트 비대상(GROUP_B·corporate_events*·{@code rawRowCount ≥ 100}) — 기존 종료 판정 경로 무변경. */
    NOT_APPLICABLE,

    /** 신뢰 하한(벽 상수·검증 기준선)에 이미 도달 — 프로브 0회, 즉시 COMPLETED+verified (REQ-150). */
    FLOOR_ALREADY_MET,

    /** 확인 프로브가 빈 정상 응답 — 하한 확정, COMPLETED+verified (REQ-147/-150). */
    CONFIRMED_EXHAUSTED,

    /** 확인 프로브가 1행 이상 — 조기 완료 차단, IN_PROGRESS 유지+진행점 전진+조기완료 카운터++ (REQ-148/-150/-157). */
    MORE_DATA_EXISTS,

    /** 확인 프로브 API 오류 — 검증 보류·무전진 (REQ-149). */
    DEFERRED,

    /**
     * 0 유효행·{@code oldest==null}·{@code rawRowCount==0}(진짜 빈 응답) + 교차검증 통과 — 소진 검증,
     * COMPLETED+verified (REQ-146a).
     */
    EMPTY_EXHAUSTED,

    /**
     * 이상(anomaly) — 완료 금지, bounded FAILED (REQ-146b): (②③) {@code oldest==null·rawRowCount>0}(zdiv
     * 가드/전량 거부), 또는 (①강등) {@code rawRowCount==0}이나 교차검증 실패({@code anchor > floor}, R5-MA-01).
     */
    EMPTY_ANOMALY
}
